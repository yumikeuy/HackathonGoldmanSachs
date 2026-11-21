# Load tests (k6)

Owner: **Role 2**. Results and how to read them: [`docs/load-testing.md`](../docs/load-testing.md).

Run against a running service (`docker compose up --build`); no other data is needed — each script creates its own
source, team and rule over the REST API with unique names.

```bash
docker run --rm --network host -v "$PWD/loadtest":/scripts grafana/k6 run /scripts/burst.js
docker run --rm --network host -v "$PWD/loadtest":/scripts grafana/k6 run /scripts/mixed.js
docker run --rm --network host -v "$PWD/loadtest":/scripts -e RATE=300 -e DURATION=2m grafana/k6 run /scripts/mixed.js
```

| Script | What it proves | Fails when |
|---|---|---|
| `burst.js` | AGENTS.md §2.5 — 500 identical events in a minute → **one** alert with `occurrencesCount = 500` | any event rejected, ingest p95 ≥ 300 ms, not exactly one alert with the exact counter |
| `mixed.js` | throughput and latency with the sample events (`payloads/`) at a constant rate | > 1 % errors, ingest p95 ≥ 250 ms / p99 ≥ 500 ms, dropped iterations |

Env overrides: `BASE_URL`; burst — `EVENTS`, `WINDOW_SEC`, `VUS`; mixed — `RATE`, `DURATION`, `MAX_VUS`.
`payloads/` are copies of `backend/src/test/resources/events/` (k6 can only read files next to the script).
