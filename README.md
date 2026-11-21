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

Login is off by default; `JWT_ENABLED=true docker compose up -d` enforces it (admin `admin@alerting.local` / `admin`,
seeded users `demo-password`) — see [docs/security.md](docs/security.md).

Then watch the alerts in the UI (or `GET /api/alerts`) and the notifications in MailHog and
`GET /api/simulator/deliveries`.

## What is real and what is mocked

The whole pipeline is real: events go through Kafka, alerts and the delivery queue live in Postgres, deduplication
and counters in Redis, and every notification is rendered, rate-limited, retried and recorded exactly as it would be
in production. Only the **last hop to the outside world** and the infrastructure size are simulated:

| Part | In this repository | In production |
|---|---|---|
| **Email** | real SMTP to **MailHog** — a fake inbox at http://localhost:8025, nothing leaves the machine | any SMTP server (`MAIL_HOST`) |
| **SMS** | **simulated**: the provider logs the message; every attempt (text, status, retries) is visible in `GET /api/simulator/deliveries` and live on WebSocket `/topic/simulator` | an SMS gateway behind the same `NotificationProvider` interface |
| **Phone call** | **simulated** the same way as SMS | a voice provider behind the same interface |
| **UI notification** | real — WebSocket push to the live feed | same |
| **Provider outages** | simulated on demand: `POST /api/simulator/channels/{EMAIL\|SMS\|CALL\|UI}/toggle` switches a channel down; messages are retried (2/5/15/60 s) and delivered when it comes back | real provider errors |
| **Event producers** | sample events in `backend/src/test/resources/events/`, the generator `POST /api/dev/generate` and k6 scripts in `loadtest/` — all synthetic | your monitoring systems, brokers and log files |
| **Infrastructure** | one container each of Postgres, Kafka (KRaft), Redis in `docker-compose.yml` | replicated / managed services (see [docs/operations.md](docs/operations.md)) |
| **On-call schedules, escalations** | not built (team decision): a rule notifies the active users of its team, each on the channel their preferences and working hours allow | — |
| **Runbook steps** | runbooks of any format are stored, attached and linked — **shown, not executed** (no automatic actions or approvals) | — |
| **User login** | none on `main`; event sources can be protected with per-source API keys ([docs/security.md](docs/security.md)) | UI login with JWT and roles (prepared on branch `role1/auth`, opt-in) |
| **Secrets, TLS** | development defaults in compose; no TLS | secrets from the platform; TLS terminated in front of the service |

## Highlights

- **Any schema, no code change** — conditions find fields by exact path or, in flexible mode, by key name anywhere
  in the event (`trade.notional` also matches `payload.Notional`); a new source is one REST call.
- **Two-layer deduplication** — Redis decides cheaply, a Postgres unique index guarantees it; losing Redis never
  produces a duplicate alert. Measured: 500 identical events in a minute → **one** alert counted 500.
- **Team suggestions for unrouted events** — near-miss conditions plus the source's routing history rank the teams an
  unknown event probably belongs to.
- **Dry-run on live traffic** and **field autocompletion** with value cardinality (HyperLogLog) for the rule editor.
- **Three source types** — HTTP webhook, Kafka topic, tailed log file.
- **Delivery that never loses a message** — transactional outbox, retries, per-user rate limit with a digest of the
  excess, provider outage simulation.
- **Safe runbooks of any format** — stored as uploaded, chosen per alert by condition, served so they cannot run code.
- **Observable** — Prometheus metrics for ingest, processing delay, alerts and delivery; k6 load tests with measured
  results in [docs/load-testing.md](docs/load-testing.md) (ingest p95 11–24 ms, processing p50 26 ms).

## Documentation

| Topic | File | Owner |
|---|---|---|
| Ingest, sources, delivery, simulator | [docs/ingest-delivery.md](docs/ingest-delivery.md) | Role 1 |
| Running, configuration, seed, smoke, metrics, troubleshooting | [docs/operations.md](docs/operations.md) | Role 1 |
| Security: threat model, login (JWT) and roles, source API keys, safe downloads, limits | [docs/security.md](docs/security.md) | Role 1 |
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

## How we used AI tools — and how we handled security and privacy

**How:** we used AI coding assistants (Claude Code, among others — each team member lists their own tools in the
video) as a pair programmer, under the rules of our design document and per-role ownership:

- scaffolding the project and writing code against agreed contracts — entities, repositories, migrations, the
  cross-role interfaces with stubs, the API contract `frontend/openapi.yaml`;
- implementing modules (for example event processing, deduplication, the generator and the k6 load tests);
- reviewing other branches before merging (catching, for example, branches that compiled alone but not on `main`),
  writing tests, and running the test suite and load tests;
- drafting documentation, which we then checked against the code.

AI-written changes went through the same pull-request review as any other change and had to compile and pass the
test suite (unit and Testcontainers integration tests on real Postgres, Kafka and Redis) before they were merged.

**Security and privacy:**

- no secrets, credentials or personal data were given to the assistants; the repository contains only development
  defaults (compose passwords, a dev JWT key on the auth branch) that must be replaced in production;
- all data used in development and tests is synthetic — invented events, users and phone numbers (`bank.example`
  addresses); no customer or production data was ever used;
- generated code went through the same review as hand-written code, with a dedicated security pass — see
  [docs/security.md](docs/security.md) for the threat model and protections (source API keys, safe runbook serving,
  ingest limits, origin allow-lists, security headers).
