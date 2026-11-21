# Flexible Alerting & Notification Service

Accepts events of **any JSON shape** from HTTP, Kafka and log files, decides by configurable conditions which **team**
cares, opens **deduplicated alerts**, attaches a **runbook** file and notifies the team's users by
**Email / SMS / Call / UI** according to their preferences and working hours. Hackathon 2026.

> Owner of this file: **Role 1**. Other roles document their area in `docs/` (see below) — link, don't edit here.

## Quick start

Needs Docker only.

```bash
docker compose up --build -d     # Postgres, Kafka, Redis, MailHog and the service (with the UI when frontend/ is built)
infra/seed/seed.sh               # demo sources, teams, users, rules and runbooks (idempotent)
infra/smoke.sh                   # end-to-end check: events → alerts → email; exits non-zero on failure
```

| What | Where |
|---|---|
| UI | http://localhost:8080 |
| REST API (Swagger) | http://localhost:8080/swagger-ui.html — contract: [`frontend/openapi.yaml`](frontend/openapi.yaml) |
| Sent emails (MailHog) | http://localhost:8025 |
| Metrics | http://localhost:8080/actuator/prometheus |

Send events — three source types, no code change for a new event shape:

```bash
# HTTP
curl -X POST localhost:8080/api/ingest/payments-monitoring -H 'Content-Type: application/json' \
     -d @backend/src/test/resources/events/rates-trade-rejected.json

# Kafka (source "workflow-events" consumes topic workflow.events)
tr -d '\n' < backend/src/test/resources/events/pricing-workflow-failed.json | docker compose exec -T kafka \
     /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic workflow.events

# Log file (source "app-log" tails infra/logs/app.log)
echo "$(date -u +%FT%TZ) ERROR payment-service: connection pool exhausted" >> infra/logs/app.log
```

Then watch the alerts in the UI (or `GET /api/alerts`) and the notifications in MailHog and
`GET /api/simulator/deliveries`.

## Documentation

| Topic | File | Owner |
|---|---|---|
| Ingest, sources, delivery, simulator | [docs/ingest-delivery.md](docs/ingest-delivery.md) | Role 1 |
| Running, configuration, seed, smoke, metrics, troubleshooting | [docs/operations.md](docs/operations.md) | Role 1 |
| Event processing, deduplication, alerts | [docs/processing.md](docs/processing.md) | Role 2 |
| Event generator and load testing | [docs/load-testing.md](docs/load-testing.md) | Role 2 |
| Condition language | [docs/conditions.md](docs/conditions.md) | Role 3 |
| Runbooks | [docs/runbooks.md](docs/runbooks.md) | Role 3 |

Design and conventions: `AGENTS.md` (shared with the team separately). Work split: [`roles.md`](roles.md).

## Architecture in one picture

```
HTTP /api/ingest/{source} ─┐
Kafka topics (sources)  ───┼─▶ events.ingest (Kafka, 6 partitions) ─▶ processor: teams → rules → dedup → alert
Log files (sources)     ───┘                                              │  (Postgres + Redis, one transaction)
                                                                          ├─▶ runbook attached
                                                                          ├─▶ delivery_outbox ─▶ worker ─▶ Email / SMS / Call / UI
                                                                          └─▶ WebSocket push to the UI
```

One Spring Boot application (modular monolith) plus Postgres, Kafka, Redis and MailHog.

## Repository layout

```
backend/     Spring Boot 4 modular monolith (Java 21): ingest, rules, runbooks, delivery, common
frontend/    React UI and the API contract openapi.yaml
infra/       seed data (infra/seed), smoke test (infra/smoke.sh), tailed log files (infra/logs)
loadtest/    k6 scenarios
docs/        documentation per area
demo/        demo script and slides
```

## Development

```bash
docker compose up -d postgres kafka redis mailhog   # infrastructure only
cd backend && mvn spring-boot:run                    # or run AlertingApplication from the IDE (port 8080)
cd backend && mvn test                               # unit + Testcontainers tests (Docker required)
```

No local Java? See "Build without local Java" in [docs/operations.md](docs/operations.md).
