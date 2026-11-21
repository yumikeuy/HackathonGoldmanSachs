# Operations

> Owner: **Role 1**. Running locally and in Docker, configuration properties, seed data, smoke test, metrics,
> troubleshooting.

## Running

| Mode | Command |
|---|---|
| Everything in Docker | `docker compose up --build -d` |
| Infrastructure in Docker, service from the IDE | `docker compose up -d postgres kafka redis mailhog`, then run `AlertingApplication` |
| Stop | `docker compose down` (add `-v` to wipe the database, Kafka and Redis data) |

Ports: service 8080, Postgres 5432 (`alerts`/`alerts`), Kafka 29092 from the host / 9092 inside compose, Redis 6379,
MailHog SMTP 1025 and UI 8025.

The image (`backend/Dockerfile`, built from the repository root) compiles the React UI from `frontend/` into the
service's static resources when `frontend/package.json` exists, then builds the jar.

The service starts without Redis (config changes then reach only the instance that made them until Redis is back) and
without Kafka (ingest answers 503 until it is back). Postgres is required.

## Configuration

All properties have working defaults for local runs (`backend/src/main/resources/application.yml`); docker-compose
overrides the hosts with environment variables.

| Environment variable | Property | Default | Meaning |
|---|---|---|---|
| `DB_HOST`, `DB_USER`, `DB_PASSWORD` | `spring.datasource.*` | `localhost`, `alerts`, `alerts` | Postgres; statement timeout 5 s, lock timeout 2 s |
| `KAFKA_BOOTSTRAP` | `spring.kafka.bootstrap-servers` | `localhost:29092` | Kafka |
| `KAFKA_CONCURRENCY` | `spring.kafka.listener.concurrency` | 6 | processor threads (≤ partitions of `events.ingest`) |
| `REDIS_HOST` | `spring.data.redis.host` | `localhost` | Redis |
| `MAIL_HOST` | `spring.mail.host` | `localhost` | SMTP server (MailHog) |
| `PUBLIC_URL` | `alerting.public-url` | `http://localhost:8080` | base of links in notifications (runbook files) |
| `DEV_GENERATOR_ENABLED` | `alerting.dev.generator-enabled` | `true` | test event generator (Role 2) |
| — | `alerting.ingest.send-timeout` | `5s` | how long ingest waits for Kafka before 503 |
| — | `alerting.outbox.poll-interval` | `500ms` | outbox worker period |
| — | `alerting.outbox.cleanup-cron` | hourly | deletion of SENT rows older than a day |
| — | `alerting.file-source.poll-interval` | `500ms` | how often file sources are checked |
| — | `alerting.mail.from` | `alerts@alerting.local` | sender of emails |
| `INGEST_RATE_LIMIT` | `alerting.ingest.rate-limit-per-second` | 1000 | events/s per HTTP source (429 above; 0 disables) |
| `ALLOWED_ORIGINS` | `alerting.security.allowed-origins` | `http://localhost:*,http://127.0.0.1:*` | browser origins allowed cross-origin (CORS, WebSocket) |
| — | `alerting.ingest.max-body-size`, `max-json-depth` | `1MB`, 64 | event size and nesting limits |

| `JWT_ENABLED` | `alerting.security.jwt.enabled` | `false` | enforce UI/API login (JWT) |
| `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` | `alerting.security.*` | dev defaults | token key and the built-in administrator — change outside development |

Security settings and their reasons: [security.md](security.md).

## Seed data

`infra/seed/seed.sh` (bash + curl) creates the demo configuration over the REST API; JSON bodies are next to it.
`BASE_URL` overrides the target (default `http://localhost:8080`).

| What | Seeded |
|---|---|
| Sources | `payments-monitoring` (http), `legacy-gateway` (http), `workflow-events` (kafka, topic `workflow.events`), `app-log` (file, `/data/logs/app.log`) |
| Team Rates | `desk == "rates"`; Anna (SMS 08–20 Warsaw, else email in working hours), Piotr (email any time); rule *Rejected trades* — HIGH, grouped by `instrument`; runbook `trade-rejection.md` |
| Team Pricing | `team == "pricing"` or `workflow` like `*-pricing`; Maria (call at night 22–07 Berlin, email any time); rule *Failed workflows* — CRITICAL, grouped by `workflow`, auto-resolved by `status == "OK"`; runbook `pricing-workflow-restart.txt` |
| Team Infra | `metric in [cpu, memory]` or `message` contains `ERROR`; John (email any time); rules *High CPU* (`value > 90`, per host, `per_window`) and *Application errors in logs*; global runbook `high-cpu.yaml` |

The script logs in as the built-in administrator first (works with and without `JWT_ENABLED`). Seeded users can log
in with the password `demo-password`: Anna and Maria (OPERATOR), Piotr (VIEWER), John (ADMIN).

Idempotent: existing sources, teams, users (by email), rules (by name within the team) and runbooks (by file name) are
skipped — a second run reports `0 created`. To change seeded data, edit it in the UI/API or wipe with
`docker compose down -v`.

## Smoke test

`infra/smoke.sh` — run after the seed against the compose stack:

1. sends every sample event from `backend/src/test/resources/events` (the workflow event through the Kafka topic) and
   appends an `ERROR` line to `infra/logs/app.log`;
2. checks alerts: Team Rates HIGH, Team Pricing CRITICAL, Team Infra HIGH (CPU) and MEDIUM (log line), the unknown
   schema as UNROUTED;
3. sends the trade event again and checks that the same alert's `occurrencesCount` grew;
4. checks that the email for the log-line alert reached MailHog.

Exit code 0 = passed; prints the failing step otherwise. Safe to run repeatedly (it resolves the log alerts of
earlier runs first). `BASE_URL`, `MAILHOG_URL`, `TIMEOUT` (seconds per wait, default 30) can be overridden.

## Tests

`cd backend && mvn test` — unit tests plus Testcontainers integration tests (Docker must be running). Notable Role 1
tests: `IngestIntegrationTest` (HTTP, Kafka and file sources → `events.ingest`, DLT + replay, lag),
`DeliveryIntegrationTest` (channel outage without loss, retry exhaustion, rate-limit digest), `FileTailerTest`
(rotation, truncation, retry of a refused line) and `ApiContractTest` — the implemented REST operations equal the
contract `frontend/openapi.yaml` (operations marked `x-planned: true` must not be implemented yet).

Build without local Java:

```bash
cd backend && docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost -v m2cache:/root/.m2 -v "$PWD":/app -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

## Metrics and health

- `/actuator/health` — liveness/readiness (Postgres, Redis, mail).
- `/actuator/prometheus` — Micrometer metrics; the service's own are `alerting.*` (ingest and delivery:
  [ingest-delivery.md](ingest-delivery.md#metrics); processing: [processing.md](processing.md)).

## Troubleshooting

| Symptom | Check |
|---|---|
| `POST /api/ingest/...` → 503 | Kafka is down or slow: `docker compose ps kafka`, `docker compose logs kafka` |
| → 404 | the source does not exist or is inactive: `GET /api/sources` |
| → 401 | the source has an API key (`apiKeyRequired: true`): send `X-Api-Key`, or remove the key with `DELETE /api/sources/{id}/api-key` |
| → 429 | more than `INGEST_RATE_LIMIT` events/s from one source — slow down or raise the limit |
| UI on another port cannot call the API / open `/ws` | add its origin to `ALLOWED_ORIGINS` |
| Event accepted but no alert | `GET /api/ingest/lag` (processor behind?); `GET /api/alerts?unrouted=true` (no team matched); `GET /api/events/failed` (processing failed) |
| Kafka source not consumed | `config.topic` correct and source active; logs: `Listening to Kafka source` |
| File source not ingesting | the path is **inside the container** (`/data/logs/...` = `./infra/logs/...`); only lines appended after the source was created are read; logs: `Tailing file source` |
| No email | MailHog at :8025; `GET /api/simulator/deliveries` (status, `lastError`); is EMAIL switched off in `GET /api/simulator/channels`?; does the user have an email and a preference that applies now (hours, working hours, time zone)? |
| Deliveries stuck PENDING with `lastError` | the channel is down (simulator switch) or SMTP unreachable — they retry after 2/5/15/60 s, then FAILED |
| Config change not picked up by another instance | Redis down — changes reach other instances once it is back (`Subscribed to Redis channel config.changed` in the log) |
| Start from scratch | `docker compose down -v && docker compose up --build -d && infra/seed/seed.sh` |
