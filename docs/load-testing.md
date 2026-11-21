# Event generator and load testing

> Owner: **Role 2**. Code: `backend/src/main/java/com/hackathon/alerting/dev` (generator), `loadtest/` (k6).

## Event generator — `POST /api/dev/generate`

Sends events through the **real** ingest path (Kafka with `acks=all`, recent events, field catalog), so they behave
exactly like events posted to `/api/ingest/{source}`. Answers `202` at once and works in the background.

```bash
curl -X POST localhost:8080/api/dev/generate -H 'Content-Type: application/json' -d '{
  "source": "payments-monitoring", "count": 200, "ratePerSecond": 20,
  "template": {"type": "TRADE_REJECTED", "desk": "rates", "instrument": "{{oneOf:EUR-IRS-10Y|USD-IRS-5Y}}",
               "trade": {"id": "T-{{seq}}", "notional": "{{randInt:1000000:50000000}}"}}}'
```

| Field | Default | Notes |
|---|---|---|
| `source` | — | an **active** source (else 400) |
| `count` | 100 | 1 … 100 000 |
| `ratePerSecond` | 100 | 1 … 1 000 |
| `template` | built-in samples | omit to cycle through the sample events (`backend/src/main/resources/dev/samples/`) |

Placeholders in string values (any depth; keys are never changed):

| Placeholder | Becomes |
|---|---|
| `{{seq}}` | number of the event in the run: 1, 2, 3 … |
| `{{uuid}}` | a random UUID |
| `{{now}}` | current time, ISO-8601 UTC |
| `{{randInt:MIN:MAX}}` | random integer, bounds inclusive |
| `{{oneOf:A\|B\|C}}` | one of the listed values |

A string that is **exactly** `{{seq}}` or `{{randInt:…}}` becomes a JSON number, so numeric conditions work in strict
mode too. Unknown or malformed placeholders → 400.

Limits: at most 4 runs at a time (409 beyond). Each send waits for Kafka's acknowledgement, so one run tops out at
roughly 1 / ack-latency events per second — enough for demos; use k6 for load. Switch the endpoint off with
`DEV_GENERATOR_ENABLED=false` (it then answers 404).

## k6 scenarios — `loadtest/`

Each script creates its own source, team and rule over the REST API (unique names), so it runs against any database.

```bash
docker compose up --build -d
docker run --rm --network host -v "$PWD/loadtest":/scripts grafana/k6 run /scripts/burst.js
docker run --rm --network host -v "$PWD/loadtest":/scripts -e RATE=100 grafana/k6 run /scripts/mixed.js
```

| Script | Proves | Fails when |
|---|---|---|
| `burst.js` | AGENTS.md §2.5: 500 identical events in one minute → **one** alert with `occurrencesCount = 500` | an event is rejected, ingest p95 ≥ 300 ms, or not exactly one alert with the exact counter |
| `mixed.js` | throughput and latency with the four sample events at a constant rate | > 1 % errors, ingest p95 ≥ 250 ms or p99 ≥ 500 ms, dropped iterations |

## Results (2026-11-21)

Machine: Intel Core Ultra 9 275HX (24 cores), 30 GB RAM, everything in Docker on one host
(`docker compose up --build`: backend, Postgres 16, Kafka KRaft, Redis 7, MailHog). Default settings
(6 partitions, `KAFKA_CONCURRENCY=6`).

| Scenario | Events | Ingest latency (HTTP 202) | Errors | Processing | Result |
|---|---|---|---|---|---|
| `burst.js` — 500 identical in 60 s | 500 | p95 24 ms, max 215 ms | 0 | one alert, `occurrencesCount = 500` | **pass** |
| `mixed.js` — 100 events/s for 60 s | 6 001 | p95 11 ms, p99 12 ms | 0 | consumer lag 0 at the end | **pass** |
| `mixed.js` — 500 events/s for 60 s | 30 000 | p95 13 ms, p99 14 ms | 0 | lag 1 712 at the end (one partition), 0 five seconds later | **pass** (ingest) |

Processing delay (`alerting_processing_delay_seconds`, time from `ingested_at` to processed) after the runs:
p50 26 ms; p95 2.8 s and p99 3.2 s, both driven by the 500 events/s run.

### How to read the 500 events/s run

Ingestion keeps up easily; **processing of a single source** is the limit. Messages on `events.ingest` are keyed by
`source_name` (AGENTS.md §3), so one source's events land on **one partition** and are processed in order by one
consumer — about **470 events/s per source** on this machine (each event: Redis dedup round trips; new alerts also a
Postgres transaction). The backlog was entirely on one partition and drained within seconds after the burst.

Several sources are processed in parallel up to the 6 partitions. If one source must go faster, the options are a
different message key (e.g. the event id — this loses per-source ordering) or pipelining the per-event Redis calls.

## Metrics to watch during a run

`/actuator/prometheus`: `alerting_events_processed_total{outcome}`, `alerting_alerts_created_total{severity}`,
`alerting_alerts_repeats_total`, `alerting_processing_delay_seconds` (p50/p95/p99), plus `GET /api/ingest/lag` for the
consumer lag per partition.
