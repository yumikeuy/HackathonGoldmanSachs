# Runbooks

> Owner: **Role 3**. Code: `backend/src/main/java/com/hackathon/alerting/runbooks`. See also [conditions.md](conditions.md) and [operations.md](operations.md).

Runbooks are operational instructions (Markdown, PDF, plain text, YAML, images, scripts, etc.) attached automatically to alerts and linked in UI cards and outgoing notifications.

---

## 1. Format-Agnostic Design

- **Any file format works without code changes.** Uploaded bytes are stored verbatim in `runbooks.content` (`bytea`).
- **No parsing or template substitution inside files.** The service never inspects file contents; judges can upload a new `.pdf`, `.docx`, `.yaml`, `.md`, or `.txt` runbook during the demo and attach it immediately.
- **Bytes stay off the hot path:**
  - `Runbook` marks `content` as `@Basic(fetch = FetchType.LAZY)`.
  - List endpoints (`GET /api/runbooks`) and the in-memory `RunbookCache` select only metadata projections (`RunbookSummary`: `id`, `teamId`, `fileName`, `condition`) via `RunbookRepository` — `content` is read exclusively by `RunbookRepository.findFileById(id)` during `GET /api/runbooks/{id}/file`.

---

## 2. REST API & 10 MB Upload Limit

All endpoints live in `runbooks.api` (`RunbookController`, `RunbookCrudService`) and publish `ConfigKinds.RUNBOOKS` after transaction commit so every instance reloads its `RunbookCache` via Redis Pub/Sub.

| Method & Path | Purpose | Notes |
|---|---|---|
| `GET /api/runbooks?teamId={id}` | List metadata without file bytes | With `teamId`, returns that team's runbooks plus global runbooks sorted by `id`; without `teamId`, returns all runbooks. |
| `POST /api/runbooks` | Upload runbook (`multipart/form-data`) | Parts/params: `file` (required, non-empty), `teamId` (optional; omit for global), `condition` (optional JSON condition string; omit for override-only). Returns `201` with `fileUrl: "/api/runbooks/{id}/file"`. |
| `GET /api/runbooks/{id}` | Get metadata | `404` if missing. |
| `PUT /api/runbooks/{id}` | Update `teamId`, `condition`, and optionally replace `file` | `file` is optional; omitting `file` keeps the existing filename and bytes. |
| `DELETE /api/runbooks/{id}` | Delete runbook | Returns `204`. Referenced `rules.override_runbook_id` and `alerts.runbook_id` clear safely via `ON DELETE SET NULL`. |
| `GET /api/runbooks/{id}/file` | Download raw file bytes | Sets `Content-Type` from `fileName`: Spring `MediaTypeFactory.getMediaType` plus explicit mappings for `.md`/`.markdown` (`text/markdown`) and `.yaml`/`.yml` (`application/yaml`), falling back to `application/octet-stream`; `Content-Disposition: inline; filename*=UTF-8''...`. |

### Filename Sanitization & Upload Limit

- Directory prefixes from Unix or Windows clients (`dir/file.pdf`, `C:\Users\ops\file.pdf`) are stripped so only the leaf filename (`≤ 255` characters) is stored.
- Maximum upload size is enforced at `10 MB` (`spring.servlet.multipart.max-file-size=10MB`). Requests above 10 MB receive `413 Payload Too Large` (`application/problem+json`); note that Tomcat closes oversized multipart connections immediately after sending the 413 response, so browser clients should validate file size before submitting.

### Sample `curl` Requests

[`infra/seed/seed.sh`](../infra/seed/seed.sh) automatically uploads the three sample runbooks below into a running environment. You can also upload and download them manually from `backend/src/test/resources/runbooks/`:

```bash
# 1. Team-scoped Markdown runbook (Team Rates = teamId 1)
curl -X POST http://localhost:8080/api/runbooks \
  -F "file=@backend/src/test/resources/runbooks/trade-rejection.md" \
  -F "teamId=1" \
  -F 'condition={"path":"type","op":"==","value":"TRADE_REJECTED"}'

# 2. Team-scoped plain-text runbook (Team Pricing = teamId 2)
curl -X POST http://localhost:8080/api/runbooks \
  -F "file=@backend/src/test/resources/runbooks/pricing-workflow-restart.txt" \
  -F "teamId=2" \
  -F 'condition={"path":"status","op":"==","value":"FAILED"}'

# 3. Global YAML runbook (omit teamId)
curl -X POST http://localhost:8080/api/runbooks \
  -F "file=@backend/src/test/resources/runbooks/high-cpu.yaml" \
  -F 'condition={"path":"metric","op":"in","value":["cpu","memory"]}'

# 4. Download inline with derived Content-Type (e.g. text/markdown for .md)
curl -i http://localhost:8080/api/runbooks/1/file
```

### Notification Links

Notifications render links rather than attaching files (`delivery.outbox.MessageComposer`): when `alert.getRunbook()` is set, Email/SMS/CALL and UI outbox payloads include `runbookUrl = {alerting.public-url}/api/runbooks/{id}/file` (default `http://localhost:8080`).

---

## 3. In-Memory `RunbookCache`

Implemented in `runbooks.cache` (`RunbookCache`, `RunbookCatalog`, `CompiledRunbook`):

- **Startup:** `RunbookCache` implements `SmartInitializingSingleton` so all runbooks are loaded and compiled before Kafka consumer containers start.
- **Hot reload:** Listens for `ConfigChangedEvent` on both `ConfigKinds.RUNBOOKS` and `ConfigKinds.TEAMS` (a team's `strict` flag governs how that team's runbook conditions compile; global runbooks always compile with `strict = false`).
- **Partitioning:**
  - `byId` holds metadata for every runbook (including override-only rows with `condition = null`).
  - `byTeamId` and `globalRunbooks` contain only auto-matchable runbooks with non-null conditions, preserving ascending `id` order for deterministic tie-breaking.
- **Resilience:** If a stored condition cannot compile (`InvalidConditionException`) or throws during evaluation, the cache logs the issue and excludes that entry from auto-matching without failing startup or reload.

---

## 4. Automatic Selection Priority (`RunbookService.attach`)

When `AlertCreator` opens a new rule-based alert inside its `@Transactional` boundary (`Propagation.MANDATORY`), it invokes `RunbookService.attach(alert, rawPayload)` (`DefaultRunbookService`). (`UnroutedEventHandler` does not attach runbooks to `UNROUTED` alerts; see [processing.md](processing.md).)

1. **Rule override (`reason: "override"`)** — if `alert.getRule().getOverrideRunbook()` is set, attach that runbook regardless of its `condition` (this is the only way a runbook with `condition = null` attaches).
2. **Team runbooks (`reason: "team"`)** — otherwise, if `alert.getTeam()` is set, select the first (lowest `id`) cached runbook belonging to that team whose compiled condition matches the event.
3. **Global runbooks (`reason: "global"`)** — otherwise, select the first (lowest `id`) cached global runbook (`team_id IS NULL`) whose compiled condition matches the event when no team runbook matched.
4. **No match** — leaves `alert.runbook` `null` and returns silently without throwing.

Attachment assigns `runbookRepository.getReferenceById(id)` (no extra `SELECT` on `runbooks`, never loading `content`), letting Hibernate dirty-checking persist `alerts.runbook_id` within the caller's transaction.

---

## 5. Audit Trail

Whenever `DefaultRunbookService` attaches a runbook, it writes an audit entry in the same transaction via `AuditService.log(...)`:

- **Actor:** `"system"` (`AuditLog.SYSTEM_ACTOR`)
- **Action type:** `"RUNBOOK_ATTACHED"` (`AuditActions.RUNBOOK_ATTACHED`)
- **Details JSON:**
  ```json
  {
    "runbookId": 1,
    "fileName": "trade-rejection.md",
    "reason": "team"
  }
  ```
  (`reason` is one of `"override"`, `"team"`, or `"global"`.)

Clients retrieve alert history oldest-first via `GET /api/alerts/{id}/audit` (`AuditController` → `AuditQueryService` → `AuditEntry` in `runbooks.audit`). Unknown alerts return `404 Not Found`.
