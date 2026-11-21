# Sample runbooks

Shared fixtures: Role 3's runbook tests, Role 1's seed script (`infra/seed`) and the demo upload them as is.
The service never parses runbooks — the formats differ on purpose. Anyone may add files; existing ones are changed by Role 3.

| File | Intended for | Suggested condition |
|---|---|---|
| `trade-rejection.md` | Team Rates, rejected trades | `{"path":"type","op":"==","value":"TRADE_REJECTED"}` |
| `pricing-workflow-restart.txt` | Team Pricing, failed workflows | `{"path":"status","op":"==","value":"FAILED"}` |
| `high-cpu.yaml` | infrastructure, CPU/memory alerts (global) | `{"path":"metric","op":"in","value":["cpu","memory"]}` |
