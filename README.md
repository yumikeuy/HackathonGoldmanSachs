# Flexible Alerting & Notification Service

A service that accepts events of **any JSON shape** from HTTP webhooks, Kafka topics and log files, decides by
configurable conditions which **team** cares, opens **deduplicated alerts**, attaches the right **runbook** file and
notifies the team's people by **Email, SMS, phone call and a live UI**, respecting each person's channel preferences,
working hours and time zone. Built for Hackathon 2026.

> Owner of this file: **Role 1**. Each area is documented in depth in [`docs/`](docs) — this page is the map.

**Contents:** [Quick start](#quick-start) · [What we built](#what-we-built) · [Requirements coverage](#requirements-coverage) ·
[Demo scenario](#demo-scenario) · [What is real and what is mocked](#what-is-real-and-what-is-mocked) ·
[Architecture](#architecture) · [Repository layout](#repository-layout) · [Development and tests](#development-and-tests) ·
[Documentation](#documentation) · [How we used AI tools](#how-we-used-ai-tools--and-how-we-handled-security-and-privacy)

---

## Quick start

Needs Docker only.

```bash
JWT_ENABLED=true docker compose up --build -d   # Postgres, Kafka, Redis, MailHog and the service with the UI
infra/seed/seed.sh                              # demo sources, teams, users, rules and runbooks (idempotent)
infra/smoke.sh                                  # end-to-end check: events → alerts → email (non-zero exit on failure)
infra/demo-traffic.sh                           # live demo: a narrated stream of events through all three sources
```

| What | Where |
|---|---|
| **UI** | http://localhost:8080 — sign in as `admin@alerting.local` / `admin` |
| Seeded users (password `demo-password`) | Anna, Maria — OPERATOR · Piotr — VIEWER · John — ADMIN ([who is who](docs/operations.md#seed-data)) |
| REST API (Swagger) | http://localhost:8080/swagger-ui.html — contract: [`frontend/openapi.yaml`](frontend/openapi.yaml) |
| Sent emails (MailHog) | http://localhost:8025 |
| Metrics (Prometheus) | http://localhost:8080/actuator/prometheus |

Without `JWT_ENABLED=true` the UI opens without login (development mode). Send an event yourself — three source
types, no code change for a new event shape:

```bash
# HTTP webhook
curl -X POST localhost:8080/api/ingest/payments-monitoring -H 'Content-Type: application/json' \
     -d @backend/src/test/resources/events/rates-trade-rejected.json

# Kafka topic (the "workflow-events" source consumes workflow.events)
tr -d '\n' < backend/src/test/resources/events/pricing-workflow-failed.json | docker compose exec -T kafka \
     /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic workflow.events

# Log file (the "app-log" source tails infra/logs/app.log)
echo "$(date -u +%FT%TZ) ERROR payment-service: connection pool exhausted" >> infra/logs/app.log
```

Something not working? See [Troubleshooting](docs/operations.md#troubleshooting).

---

## What we built

### 1. Ingest: any event, three kinds of sources
- **HTTP webhook** `POST /api/ingest/{source}` — answers `202` only after Kafka acknowledged the event; `400` for
  broken JSON, `413` over 1 MB, `429` over the per-source rate limit, `503` when Kafka is down.
- **Kafka topics** — a source of type `kafka` consumes an external topic; added and removed on the fly.
- **Log files** — a source of type `file` tails a file (rotation and truncation safe); a JSON line is an event as is,
  any other line becomes `{"message": …}`, and an optional regex with named groups turns log lines into fields.
- Events are stored **exactly as received**; nothing is mapped to a fixed schema. A failing event goes to a
  dead-letter topic, is listed in the UI and can be **replayed**.
- Every accepted event feeds a **field catalog** (paths, types, value cardinality) that powers autocompletion in
  the rule editor.

→ [docs/ingest-delivery.md — Sources](docs/ingest-delivery.md#sources) ·
[Internal Kafka topics](docs/ingest-delivery.md#internal-kafka-topics) ·
[Field catalog](docs/ingest-delivery.md#recent-events-and-field-catalog-redis)

### 2. Routing: teams, rules and the condition language
- A **team** says which events are its own (`desk == "rates"`); an event may belong to several teams.
- The team's **rules** say what to do: severity, channels, grouping, title template, auto-resolve condition,
  optionally a fixed runbook. Rules are evaluated by priority; several may fire.
- **Conditions** are trees of `and` / `or` with 12 operators (`==`, `>`, `in`, `contains`, `regex`, `glob`,
  `exists`, …). In **flexible mode** a field is found by exact path or by its key name anywhere in the event
  (`metric` also matches `envelope.probe.Metric`), and numeric strings compare as numbers — so an unfamiliar event
  shape is routed without any configuration change. **Strict mode** matches exact paths and types only.
- Conditions are compiled once into predicates and cached; changes apply on every instance within a second,
  without a restart. Rules can be **dry-run** against recent live traffic before saving.

→ [docs/conditions.md](docs/conditions.md) (operators, [strict vs flexible](docs/conditions.md#4-strict-mode-vs-flexible-mode),
[missing fields](docs/conditions.md#5-missing-fields-and-json-null)) ·
[docs/processing.md — The pipeline](docs/processing.md#the-pipeline)

### 3. Alerts: deduplication, grouping, lifecycle
- Events with the same **fingerprint** (rule + values of its `group_by_paths`, e.g. one alert per instrument or per
  host) become **one alert with a counter** — measured: 500 identical events in a minute → one alert ×500; 1,500 in
  six seconds → one alert ×1,500.
- Two layers make duplicates impossible: Redis decides cheaply, a unique index in Postgres guarantees it — also with
  several instances.
- Grouping modes `while_open` and `per_window`; alerts are **acknowledged**, **resolved** by people or
  **resolved automatically** when a recovery event matches the rule's `resolve_condition`.
- Events no team matches become **UNROUTED** alerts — nothing is lost — with **team suggestions** (near-miss
  conditions plus the source's routing history) and "create a rule from this event".

→ [docs/processing.md — Grouping](docs/processing.md#grouping) · [Alert lifecycle](docs/processing.md#alert-lifecycle) ·
[Unrouted events](docs/processing.md#unrouted-events)

### 4. Runbooks of any format
- Upload any file (PDF, Markdown, Word, YAML, text … up to 10 MB); it is stored as uploaded and never parsed.
- The right runbook is attached **automatically**: a rule's fixed runbook → a team runbook whose condition matches →
  a global one. Uploading or editing a runbook **re-syncs open alerts**, so a runbook added during an incident shows
  up on it at once. Every attachment is in the alert's audit trail.
- Notifications carry a **link** to the file (signed when login is on); files are served so they cannot run code.

→ [docs/runbooks.md](docs/runbooks.md) ([selection priority](docs/runbooks.md#4-automatic-selection-priority-runbookserviceattach),
[keeping open alerts in sync](docs/runbooks.md#keeping-open-alerts-in-sync-runbookresync),
[who can manage runbooks](docs/runbooks.md#5-who-can-manage-runbooks))

### 5. Notifications: right person, right channel, nothing lost
- The users of the alert's team are notified; for each user **one** channel is chosen from their **preferences** in
  priority order, the channels the rule allows, the contact they have, their **working hours** and **time zone**
  (e.g. "SMS 08–20, otherwise email in working hours"; "phone call at night").
- Notifications go through a **transactional outbox**: written with the alert, sent by a worker, **retried** after
  2 / 5 / 15 / 60 s. At most 20 messages per person and channel per minute; the excess is merged into one digest.
  Repeats of an alert never notify again.
- A **delivery simulator** shows every attempt live and can switch a channel off to demonstrate that messages wait
  and are delivered when it comes back.

→ [docs/ingest-delivery.md — Choosing the channel](docs/ingest-delivery.md#choosing-the-channel) ·
[Outbox worker](docs/ingest-delivery.md#delivery-outbox-worker) · [Simulator](docs/ingest-delivery.md#delivery-simulator)

### 6. The UI
React single-page app served by the service itself:

| Page | What it does |
|---|---|
| **Alerts** | live feed over WebSocket (no polling, no reload), filters, counters, ack / resolve, alert card with the first event, runbook, notifications and audit trail |
| **Unrouted** | events no team matched, team suggestions, create a rule from an event |
| **Rules** | rule editor with a visual condition builder, field autocompletion and dry-run on recent events |
| **Runbooks** | upload and edit runbooks with conditions |
| **Teams & Users** | routing conditions; users with channels, working hours, time zone, role and password |
| **Sources** | HTTP / Kafka / file sources, API keys, a test event generator |
| **Simulator** | every delivery attempt live, channel on/off switches |
| **Failed Events** | events whose processing failed, replay |

### 7. Security
- **Login** with JWT access tokens and one-time (rotating) refresh tokens; roles **VIEWER** (read), **OPERATOR**
  (+ ack / resolve, own team's runbooks), **ADMIN** (configuration). Brute-force limit on login.
- **Team isolation**: a viewer or operator sees only their own team — its alerts, rules, runbooks and live feed —
  enforced on the server for REST and WebSocket, and mirrored in the UI (no foreign data, no admin pages or buttons).
- **Per-source API keys** for webhooks, safe serving of uploaded files (no stored XSS), ingest size / depth / rate
  limits, origin allow-list for CORS and WebSocket, security headers.

→ [docs/security.md](docs/security.md) ([threat model](docs/security.md#threat-model),
[login and roles](docs/security.md#login-jwt-access--refresh-tokens), [API keys](docs/security.md#source-api-keys),
[safe downloads](docs/security.md#safe-runbook-downloads))

### 8. Reliability, scale and operations
- Nothing is lost on the way: Kafka acknowledgements before `202`, manual offset commits after the database commit,
  retries and a dead-letter topic; Postgres or Redis outages are retried, never dropped.
- **Scales out**: several instances behind a load balancer share Kafka partitions, deduplication, the outbox, config
  reloads, the live feed and file sources through Kafka, Postgres and Redis. `docker-compose.scale.yml` +
  `infra/scale-demo.sh`: 200 events over two instances → one alert ×200, one notification per person.
- **Load tested** with k6 ([results](docs/load-testing.md#results-2026-11-21)); **metrics** for ingest, processing,
  alerts and delivery latency; **CI** on every pull request runs all tests and the full compose → seed → smoke path.

→ [docs/operations.md](docs/operations.md) ([configuration](docs/operations.md#configuration),
[several instances](docs/operations.md#several-instances), [CI](docs/operations.md#ci)) ·
[docs/load-testing.md](docs/load-testing.md) · [Metrics](docs/ingest-delivery.md#metrics)

---

## Requirements coverage

| Task | Requirement | How it is covered | Read more |
|---|---|---|---|
| 2.1 | ≥ 2 source types, any schema, new format without code | HTTP, Kafka **and** log files; events stored as is; flexible field lookup by path or key name | [ingest](docs/ingest-delivery.md#sources), [conditions](docs/conditions.md) |
| 2.2 | conditions on any field, AND/OR, per-rule routing and presentation, live changes | condition trees with 12 operators; teams + prioritised rules with severity, channels, title templates; hot reload over Redis; dry-run | [conditions](docs/conditions.md), [processing](docs/processing.md#the-pipeline) |
| 2.3 | channels, preferences, working hours, provider simulators, retries without loss | per-user channel choice by preferences / hours / time zone; outbox with retries, rate limit + digest; Email via SMTP, SMS / call simulated; outage switches | [delivery](docs/ingest-delivery.md#choosing-the-channel) |
| 2.4 | UI: live feed (push), ack / resolve, rule management | React UI, WebSocket live feed, ack / resolve, rule editor with condition builder and dry-run, login and roles | [UI](#6-the-ui), [security](docs/security.md) |
| 2.5 | deduplication (500/min → 1 alert with a counter), grouping | fingerprint + Redis + unique index; `group_by_paths`, `while_open` / `per_window`; measured with k6 | [processing](docs/processing.md#grouping), [load tests](docs/load-testing.md) |
| 2.6 | runbooks in different formats | any file format, attached by condition, re-synced to open alerts, linked from notifications | [runbooks](docs/runbooks.md) |

---

## Demo scenario

1. `JWT_ENABLED=true docker compose up --build -d && infra/seed/seed.sh`, open http://localhost:8080, sign in as admin.
2. Run `infra/demo-traffic.sh` next to the browser: rejected trades go to **Team Rates**, a failed workflow over
   Kafka to **Team Pricing** (and resolves itself later), CPU spikes and log errors to **Team Infra**, an unknown
   event shape to **Unrouted**, a burst of 30 identical events becomes **one** alert — all live, no reload.
   ([what each act shows](docs/operations.md#live-demo-traffic))
3. Open an alert: the first event, the attached runbook, who was notified, the audit trail. Emails in MailHog.
4. **Simulator**: switch SMS off, send events, watch retries; switch it on — every message arrives.
5. **Unrouted**: an unfamiliar event → team suggestion → "create rule from event" → the next such event is routed.
6. Sign in as Piotr (VIEWER): only Team Rates, read-only. As Anna (OPERATOR): ack and resolve her team's alerts.
7. Optional: upload a new runbook — matching open alerts get it at once; `infra/scale-demo.sh` on two instances.

---

## What is real and what is mocked

The whole pipeline is real: events go through Kafka, alerts and the delivery queue live in Postgres, deduplication
and counters in Redis, and every notification is rendered, rate-limited, retried and recorded exactly as it would be
in production. Only the **last hop to the outside world** and the infrastructure size are simulated:

| Part | In this repository | In production |
|---|---|---|
| **Email** | real SMTP to **MailHog** — a fake inbox at http://localhost:8025, nothing leaves the machine | any SMTP server (`MAIL_HOST`) |
| **SMS** | **simulated**: the provider logs the message; every attempt (text, status, retries) is visible on the Simulator page and live on `/topic/simulator` | an SMS gateway behind the same `NotificationProvider` interface |
| **Phone call** | **simulated** the same way as SMS | a voice provider behind the same interface |
| **UI notification** | real — WebSocket push to the live feed | same |
| **Provider outages** | simulated on demand with the channel switches on the Simulator page | real provider errors |
| **Event producers** | sample events in `backend/src/test/resources/events/`, `infra/demo-traffic.sh`, the generator `POST /api/dev/generate`, k6 scripts in `loadtest/` — all synthetic | your monitoring systems, brokers and log files |
| **Infrastructure** | one container each of Postgres, Kafka (KRaft), Redis in `docker-compose.yml` | replicated / managed services ([operations](docs/operations.md)) |
| **On-call schedules, escalations** | not built (team decision): a rule notifies the active users of its team, each on the channel their preferences and working hours allow | — |
| **Runbook steps** | runbooks of any format are stored, attached and linked — **shown, not executed** (no automatic actions or approvals) | — |
| **User login** | real: JWT login, roles and team isolation, switched on with `JWT_ENABLED=true` (off by default for development) | same, with an identity provider if needed |
| **Secrets, TLS** | development defaults in compose (`.env.example`); no TLS | secrets from the platform; TLS terminated in front of the service |

---

## Architecture

```
HTTP /api/ingest/{source} ─┐
Kafka topics (sources)  ───┼─▶ events.ingest (Kafka, 6 partitions) ─▶ processor: teams → rules → dedup → alert
Log files (sources)     ───┘          │                                     │  (Postgres + Redis, one transaction)
                                      └─▶ events.ingest.DLT → failed events, replay
                                                                            ├─▶ runbook attached
                                                                            ├─▶ delivery_outbox ─▶ worker ─▶ Email / SMS / Call / UI
                                                                            └─▶ WebSocket push to the UI (fanned out across instances)
```

One Spring Boot 4 application (Java 21, modular monolith: `ingest`, `rules`, `runbooks`, `delivery`, `common`) plus
Postgres 16, Kafka (KRaft), Redis 7 and MailHog. The React UI is built into the same image. Postgres is the source
of truth; Redis can be lost without losing alerts.

---

## Repository layout

```
backend/                Spring Boot application: ingest, rules (processing, alerts), runbooks, delivery, common
  src/test/             unit and Testcontainers integration tests (real Postgres, Kafka, Redis)
frontend/               React UI (Vite, TypeScript, Tailwind) and the API contract openapi.yaml
infra/seed/             demo configuration created over the REST API (seed.sh + JSON bodies)
infra/smoke.sh          end-to-end smoke test against a running stack
infra/demo-traffic.sh   narrated live demo traffic
infra/scale-demo.sh     two-instance proof (with docker-compose.scale.yml and infra/nginx/)
infra/logs/             files tailed by file sources (mounted into the container)
loadtest/               k6 scenarios
docs/                   documentation per area
.github/workflows/      CI: tests and compose → seed → smoke on every pull request
```

---

## Development and tests

```bash
docker compose up -d postgres kafka redis mailhog   # infrastructure only
cd backend && mvn spring-boot:run                    # or run AlertingApplication from the IDE (port 8080)
cd backend && mvn test                               # unit + Testcontainers tests (Docker required)
cd frontend && npm install && npm run dev            # UI on :5173, proxies /api and /ws to :8080
```

No local Java? See [build without local Java](docs/operations.md#tests). Tests worth knowing: ingest and delivery
end to end, security and team isolation over REST and WebSocket, runbook selection and re-sync, the API contract
test (implemented endpoints = `frontend/openapi.yaml`) — [docs/operations.md — Tests](docs/operations.md#tests).

---

## Documentation

| Topic | File |
|---|---|
| Ingest, sources, channel choice, delivery, simulator, metrics | [docs/ingest-delivery.md](docs/ingest-delivery.md) |
| Event processing, deduplication, grouping, alert lifecycle, unrouted events, live feed | [docs/processing.md](docs/processing.md) |
| Condition language: tree, operators, strict vs flexible, missing fields, templates | [docs/conditions.md](docs/conditions.md) |
| Runbooks: formats, API, automatic selection, re-sync, permissions, audit | [docs/runbooks.md](docs/runbooks.md) |
| Security: threat model, login and roles, team isolation, API keys, safe downloads, limits | [docs/security.md](docs/security.md) |
| Running, configuration, seed, smoke, demo traffic, tests, CI, several instances, troubleshooting | [docs/operations.md](docs/operations.md) |
| Event generator and load testing with measured results | [docs/load-testing.md](docs/load-testing.md) |
| REST and WebSocket contract | [frontend/openapi.yaml](frontend/openapi.yaml) · Swagger at `/swagger-ui.html` |
| Work split between the roles | [roles.md](roles.md) |

---

## How we used AI tools — and how we handled security and privacy

**How:** we used AI coding assistants (Claude Code, among others — each team member lists their own tools in the
video) as a pair programmer, under the rules of our design document and per-role ownership:

- scaffolding the project and writing code against agreed contracts — entities, repositories, migrations, the
  cross-role interfaces with stubs, the API contract `frontend/openapi.yaml`;
- implementing modules (for example event processing, deduplication, delivery, security, the generator and the k6
  load tests);
- reviewing other branches before merging (catching, for example, branches that compiled alone but not on `main`),
  writing tests, running the test suite, load tests and headless-browser checks of the UI;
- drafting documentation, which we then checked against the code.

AI-written changes went through the same pull-request review as any other change and had to compile and pass the
test suite (unit and Testcontainers integration tests on real Postgres, Kafka and Redis) and CI before they were merged.

**Security and privacy:**

- no secrets, credentials or personal data were given to the assistants; the repository contains only development
  defaults (compose passwords, a development JWT key, the demo admin password) that must be replaced in production;
- all data used in development and tests is synthetic — invented events, users and phone numbers (`bank.example`
  addresses); no customer or production data was ever used;
- generated code went through the same review as hand-written code, with a dedicated security pass — see
  [docs/security.md](docs/security.md) for the threat model and protections.
