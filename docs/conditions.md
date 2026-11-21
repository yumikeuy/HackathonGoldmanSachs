# Condition Language

> Owner: **Role 3**. Code: `backend/src/main/java/com/hackathon/alerting/common/condition`, `common/json`.

The alerting engine evaluates rules, team routing, auto-resolution, and runbook matching against arbitrary JSON event payloads without hardcoded schemas. Every configurable condition uses the same recursive JSON tree structure, compiled once per configuration reload into an in-memory `Predicate<JsonNode>`.

---

## 1. Where Conditions Are Used

| Location | Meaning when set | Meaning when `null` | Strict flag source |
|---|---|---|---|
| `teams.condition` | Event belongs to this team | Team receives **no events** | `teams.strict` |
| `rules.condition` | Team event fires this rule | Fires for **every** event of the team | Owning team's `strict` |
| `rules.resolve_condition` | Team event auto-closes open alerts of this rule | **Never** auto-closes alerts | Owning team's `strict` |
| `runbooks.condition` (team) | Attach runbook automatically when alert opens | **Override-only** (never auto-matched; attach only via `rules.override_runbook_id`) | Owning team's `strict` |
| `runbooks.condition` (global) | Attach global runbook when no override or team runbook matched | **Override-only** | Always `false` (flexible) |

---

## 2. Condition Tree Structure

A condition tree is made of three JSON object node shapes:

- **Conjunction (`and`)** — matches when every child condition matches (`{"and": [ <Condition>, ... ]}`). Array must be non-empty; object must not have other properties.
- **Disjunction (`or`)** — matches when at least one child condition matches (`{"or": [ <Condition>, ... ]}`). Array must be non-empty; object must not have other properties.
- **Leaf predicate** — tests one field against an operator (`{"path": "<dotted.path>", "op": "<operator>", "value": <operand>}`). Only `"path"`, `"op"`, and optional `"value"` keys are allowed; any unknown property (such as a misspelled `"vaule"`) is rejected at save time with HTTP 400. `path` must not contain surrounding whitespace or empty segments (such as `".a"`, `"a."`, or `"a..b"`).

> **Note on negation:** A `{"not": ...}` wrapper node is intentionally omitted. Use `!=`, `not_in`, or `{"op": "exists", "value": false}` for negative checks.

Example combining `and`, `or`, and multiple leaf operators (matches `infra-cpu-high.json`):

```json
{
  "and": [
    {
      "or": [
        { "path": "metric", "op": "==", "value": "cpu" },
        { "path": "metric", "op": "==", "value": "memory" }
      ]
    },
    { "path": "value", "op": ">", "value": 90 },
    { "path": "host", "op": "glob", "value": "prod-*" }
  ]
}
```

---

## 3. Operators (All 12)

Examples below use the shared fixtures in `backend/src/test/resources/events/`:
- `rates-trade-rejected.json` (`trade.notional` is string `"15000000"`)
- `pricing-workflow-failed.json` (`attempt: 3`, `error: "Timeout while fetching USD curve from market-data"`)
- `infra-cpu-high.json` (`metric: "cpu"`, `value: 97.5`, `host: "prod-db-03"`)
- `alien-schema.json` (`Header.MsgType: "DealException"`, `Body.Deal.Economics.Notional: 25000000`)

| `op` | Expected `value` | Behavior & Matching Rule | Example Leaf & Sample Payload Match |
|---|---|---|---|
| `==` | Non-null JSON scalar/value | Equals operand. Compares numbers mathematically (`10 == 10.0`); in flexible mode (`strict = false`) coerces numeric strings (`"15000000" == 15000000`). | `{"path":"trade.notional","op":"==","value":15000000}` → `true` on `rates-trade-rejected.json` (`strict = false`) |
| `!=` | Non-null JSON scalar/value | Not equal to operand (same numeric/flexible coercion rules). **Returns `false` if the field is missing or JSON `null`.** | `{"path":"status","op":"!=","value":"OK"}` → `true` on `pricing-workflow-failed.json` |
| `>` | JSON number (event field may be a numeric string when `strict = false`) | Strictly greater than `value` (`BigDecimal` comparison). | `{"path":"trade.notional","op":">","value":10000000}` → `true` on `rates-trade-rejected.json` and `alien-schema.json` (`strict = false`) |
| `>=` | JSON number (event field may be a numeric string when `strict = false`) | Greater than or equal to `value`. | `{"path":"attempt","op":">=","value":3}` → `true` on `pricing-workflow-failed.json` |
| `<` | JSON number (event field may be a numeric string when `strict = false`) | Strictly less than `value`. | `{"path":"value","op":"<","value":100}` → `true` on `infra-cpu-high.json` |
| `<=` | JSON number (event field may be a numeric string when `strict = false`) | Less than or equal to `value`. | `{"path":"value","op":"<=","value":97.5}` → `true` on `infra-cpu-high.json` |
| `in` | Non-empty JSON array (no `null` elements) | `true` if actual value equals any element in `value`. | `{"path":"metric","op":"in","value":["cpu","memory"]}` → `true` on `infra-cpu-high.json` |
| `not_in` | Non-empty JSON array (no `null` elements) | `true` if the field exists (non-null) and matches no element in `value`. **Returns `false` if missing or JSON `null`.** | `{"path":"region","op":"not_in","value":["us-east-1","ap-south-1"]}` → `true` on `infra-cpu-high.json` |
| `contains` | String | `true` if actual value is a JSON string containing `value` as a substring (case-sensitive). | `{"path":"error","op":"contains","value":"Timeout"}` → `true` on `pricing-workflow-failed.json` |
| `regex` | String (Java `Pattern`) | Compiled once on load. `true` if actual value is a JSON string where `Pattern.matcher(actual).find()` succeeds. | `{"path":"trade.currency","op":"regex","value":"^(EUR|USD)$"}` → `true` on `rates-trade-rejected.json` |
| `glob` | String (`*` and `?` wildcards) | Regex metacharacters are escaped literally; `*` matches any characters, `?` matches one character, anchored across the whole string (`^...$`). | `{"path":"host","op":"glob","value":"prod-??-*"}` → `true` on `infra-cpu-high.json` |
| `exists` | Boolean (optional; defaults to `true`) | `value: true` (or omitted/`null`): `true` when field is present and non-null. `value: false`: `true` when field is missing or JSON `null`. | `{"path":"labels.service","op":"exists","value":true}` → `true` on `infra-cpu-high.json`; `{"path":"missing","op":"exists","value":false}` → `true` |

---

## 4. Strict Mode vs Flexible Mode

Path resolution is controlled by `teams.strict` (always `false` for global runbooks and template rendering):

1. **Exact dotted path (`strict = true` and `strict = false`):**
   Segments split on `.` walk nested objects and numeric zero-based array indices (for example, `labels.service` or `items.0.price`).

2. **Flexible key fallback (`strict = false` only):**
   - If the exact dotted path is absent in the payload, the locator takes the last segment of `path` (for `trade.notional`, the segment is `notional`) and performs a **breadth-first search** across the entire JSON payload, matching object keys **case-insensitively**.
   - If the exact path **is** present with an explicit JSON `null` value (for example `{"trade":{"notional":null},"other":{"notional":999}}`), `trade.notional` counts as absent immediately and does **not** fall back to `other.notional`.
   - On `alien-schema.json`, `{"path":"trade.notional","op":">","value":10000000}` misses `trade.notional` at root, searches for key `notional`, finds `Body.Deal.Economics.Notional = 25000000`, and returns `true`.
   - **Numeric string coercion (`strict = false` only):** incoming event string values representing numbers (such as `"15000000"` in `rates-trade-rejected.json`) coerce to `BigDecimal` for `>`, `>=`, `<`, `<=`, `==`, `!=`, `in`, and `not_in`. Condition thresholds in `>`, `>=`, `<`, `<=` must always be JSON numbers so later toggling `teams.strict` cannot break stored conditions. With `strict = true`, `"15000000" > 10000000` and `"15000000" == 15000000` both evaluate to `false`.

---

## 5. Missing Fields and JSON `null`

A missing field and a field whose value is JSON `null` (`{"reason": null}`) both count as **absent**:

- Every value operator (`==`, `!=`, `>`, `>=`, `<`, `<=`, `in`, `not_in`, `contains`, `regex`, `glob`) evaluates to **`false`** on an absent or `null` field — including negations such as `!=` and `not_in`.
- `{"path": "reason", "op": "exists"}` / `value: true` evaluates to **`false`**.
- `{"path": "reason", "op": "exists", "value": false}` evaluates to **`true`**.

---

## 6. Validation Errors and Compilation

Conditions are validated synchronously whenever saved through the REST API (`teams`, `rules`, `runbooks`). Invalid trees fail with HTTP `400 Bad Request` (`application/problem+json`) containing `detail` and the exact JSON node path (starting at `$`). Endpoints under `/api/rules` and `/api/runbooks` prefix `detail` with `condition: ` (or `resolveCondition: `); `/api/teams` returns the compiler detail directly:

| Invalid input | `detail` message |
|---|---|
| `{"and":[{"path":"a","op":"equals","value":1}]}` | `Unknown operator 'equals' at $.and[0]` |
| `{"path":"a","op":"==","value":1,"vaule":2}` | `Unknown property 'vaule' in condition leaf at $` |
| `{"and":[]}` | `'$.and' must not be empty` |
| `{"and":[],"extra":1}` | `'and' node at $ must not have other properties` |
| `{"path":"trade.","op":"==","value":1}` | `Invalid path 'trade.' at $` |
| `{"path":"a","op":">","value":"90"}` | `Operator '>' at $ requires a numeric 'value'` |
| `{"path":"a","op":"regex","value":"[unclosed"}` | `Invalid regex at $: Unclosed character class` |
| `{"path":"a","op":"in","value":"EUR"}` | `Operator 'in' at $ requires a non-empty array 'value'` |

Once stored, conditions are compiled **once** during cache reloads (`RoutingCache` and `RunbookCache`), pre-compiling regex/glob `Pattern` objects and `BigDecimal` literals so event processing executes zero parsing and zero database queries per event. If a previously persisted condition ever fails compilation during reload, that entry is logged and skipped so it cannot block the remaining configuration.

---

## 7. Placeholder Templates (`{{path}}`)

Alert titles (`rules.presentation.title`) use `PlaceholderTemplateRenderer` to substitute `{{path}}` placeholders from incoming events:

- Lookups always run in **flexible mode (`strict = false`)**, so `"Rejected {{trade.id}} ({{trade.notional}})"` renders `"Rejected T-1001 (15000000)"` on `rates-trade-rejected.json` and `"Rejected  (25000000)"` (or `Deal FX-778: 25000000 USD` via `Deal {{deal.ref}}: {{NOTIONAL}} {{ccy}}`) on `alien-schema.json`.
- Missing fields or JSON `null` values render as an empty string `""`.
- Non-scalar JSON nodes (objects/arrays) serialize to compact JSON.
