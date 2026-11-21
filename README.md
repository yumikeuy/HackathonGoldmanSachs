# Flexible Alerting & Notification Service

Accepts events of **any JSON shape** from HTTP and Kafka, decides by configurable conditions which **team** cares,
opens **deduplicated alerts**, attaches a **runbook** file and notifies the team's users by **Email / SMS / Call / UI**
according to their preferences and working hours. Hackathon 2026.

> Owner of this file: **Role 1**. Other roles document their area in `docs/` (see below) — link, don't edit here.

## Quick start

```bash
docker compose up --build          # Postgres, Kafka, Redis, MailHog and the service (with the UI when frontend/ is built)
```

| What | Where |
|---|---|
| UI | http://localhost:8080 |
| REST API (Swagger) | http://localhost:8080/swagger-ui.html — contract: [`frontend/openapi.yaml`](frontend/openapi.yaml) |
| Sent emails (MailHog) | http://localhost:8025 |
| Metrics | http://localhost:8080/actuator/prometheus |

Send an event:

```bash
curl -X POST localhost:8080/api/ingest/payments-monitoring -H 'Content-Type: application/json' \
     -d @backend/src/test/resources/events/rates-trade-rejected.json
```

## Documentation

| Topic | File | Owner |
|---|---|---|
| Ingest, sources, delivery, simulator | [docs/ingest-delivery.md](docs/ingest-delivery.md) | Role 1 |
| Running, configuration, operations | [docs/operations.md](docs/operations.md) | Role 1 |
| Event processing, deduplication, alerts | [docs/processing.md](docs/processing.md) | Role 2 |
| Event generator and load testing | [docs/load-testing.md](docs/load-testing.md) | Role 2 |
| Condition language | [docs/conditions.md](docs/conditions.md) | Role 3 |
| Runbooks | [docs/runbooks.md](docs/runbooks.md) | Role 3 |

Design and conventions: `AGENTS.md` (shared with the team separately). Work split: [`roles.md`](roles.md).

## Repository layout

```
backend/     Spring Boot 4 modular monolith (Java 21): ingest, rules, runbooks, delivery, common
frontend/    React UI and the API contract openapi.yaml
infra/       infrastructure helpers (seed data, smoke test)
loadtest/    k6 scenarios
docs/        documentation per area
demo/        demo script and slides
```
