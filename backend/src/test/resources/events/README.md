# Sample events

Shared test fixtures for all roles: condition-engine unit tests (Role 3), processor tests (Role 2),
ingest `curl` checks (Role 1) and UI mocks (Role 4). Send one with:

```bash
curl -X POST localhost:8080/api/ingest/payments-monitoring -H 'Content-Type: application/json' \
     -d @backend/src/test/resources/events/rates-trade-rejected.json
```

| File | Intended route | Notes |
|---|---|---|
| `rates-trade-rejected.json` | Team Rates | nested `trade.*`, string number in `trade.notional` (flexible mode compares it as a number) |
| `pricing-workflow-failed.json` | Team Pricing | flat workflow event, `error` text for `contains` / `regex` |
| `infra-cpu-high.json` | infrastructure team | `metric` / `value` / `host` — the example condition in AGENTS.md §5 |
| `alien-schema.json` | none → UNROUTED | unfamiliar shape; same facts under other keys (`Notional` deep inside) to exercise key search |

These are illustrative until replaced by the exact examples from the task's Appendix A — keep the file names.
