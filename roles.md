# Roles — who builds what, in parallel, without merge conflicts

This file splits the **first phase** of the work (from now until the end-to-end demo path works) between four people.
It complements `AGENTS.md` (the source of truth for the domain, schema and architecture) — read that first.
If the two disagree, `AGENTS.md` wins; fix this file.

**How parallel work is possible:** step 0 is done. Every place where one role's code calls another's is a Java
interface with a **working stub**, the REST/WebSocket contract is written down in `frontend/openapi.yaml`, and every
package and shared file has exactly one owner. Each person writes only in their own packages, codes against the
interfaces, and replaces only their own stubs. Nobody waits for anybody.

---

## 1. What already exists (step 0, on `main`)

| Area | What | Where |
|---|---|---|
| Schema | 9 tables, constraints, indexes (Flyway V1 + V2) | `backend/src/main/resources/db/migration` |
| Entities | `Team`, `User`, `OutboxTask`, `Source`, `Event`, `Rule`, `Alert`, `Runbook`, `AuditLog` | `*/model` |
| Repositories | one per entity, incl. hot-path SQL (`AlertRepository.insertIfNoOpenAlert`, `addOccurrences`, `OutboxTaskRepository.lockDueBatch`) | `*/repository` |
| REST conventions | RFC 9457 errors (`NotFoundException`, `InvalidRequestException`), keyset pages (`CursorPage`, `Cursors`) | `common.web` |
| WebSocket | STOMP at `/ws`, broker `/topic`, destinations in `WebSocketTopics` | `common.config` |
| Kafka | topic names and message format (`KafkaTopics`), envelope record `EventEnvelope` | `common.config`, `ingest.envelope` |
| Config reload | `ConfigChangedPublisher` → `ConfigChangedEvent` (after commit), kinds in `ConfigKinds` | `common.config` |
| Contracts + stubs | see the table in §2 | |
| API contract | every REST endpoint + WebSocket messages | `frontend/openapi.yaml` |
| Sample events | rates / pricing / infra / foreign schema | `backend/src/test/resources/events` |
| Test infra | `@JpaRepositoryTest`; Postgres, Kafka, Redis containers; `TestData` | `backend/src/test/.../support` |

The application starts and `mvn test` is green.

---

## 2. Contracts between roles

Each interface has one implementation class (the stub). **Its owner fills in that same class** (or renames it inside
their package) — the interface and the calling code do not change.

| Interface | Owner | Stub today | Called by |
|---|---|---|---|
| `common.condition.ConditionCompiler` — `compile(JsonNode condition, boolean strict) → Predicate<JsonNode>`, throws `InvalidConditionException` (→ 400) | Role 3 | `DefaultConditionCompiler`: `null` → true, `and`/`or`, only `==` by exact path; other ops → 400 | Role 1 (team validation), Role 2 (team/rule cache, dry-run, rule validation), Role 3 (runbooks) |
| `common.json.JsonFieldLocator` — `find(event, path, strict) → Optional<JsonNode>` | Role 3 | `ExactPathFieldLocator`: exact dotted path | Role 2 (`group_by_paths`), Role 3 |
| `common.json.TemplateRenderer` — `render("Rejected {{trade.id}}", event)` | Role 3 | `PlaceholderTemplateRenderer`: works, exact paths | Role 2 (alert titles), Role 1 (notification texts) |
| `runbooks.RunbookService` — `attach(Alert, JsonNode event)` | Role 3 | `DefaultRunbookService`: does nothing | Role 2 (`AlertCreator`) |
| `runbooks.audit.AuditService` — `log(Long alertId, String actor, String actionType, JsonNode details)` | Role 3 | `DefaultAuditService`: logs only | everyone |
| `delivery.outbox.OutboxService` — `enqueue(Alert, Rule)` | Role 1 | `DefaultOutboxService`: one UI row | Role 2 (`AlertCreator`) |
| `common.config.ConfigChangedPublisher` — `publish(String what)` | Role 1 | `LocalConfigChangedPublisher`: local Spring event after commit | everyone with CRUD |
| `ingest.recent.RecentEvents` — `add(event)`, `latest(limit)` | Role 1 | `InMemoryRecentEvents`: in-memory, 1000 | Role 2 (dry-run) |

Listening for configuration changes (all caches):

```java
@EventListener
void onConfigChanged(ConfigChangedEvent event) {
    if (event.is(ConfigKinds.RULES) || event.is(ConfigKinds.TEAMS)) reload();
}
```

Kafka `events.ingest`: key = `source_name`, value = JSON string of `EventEnvelope` (snake_case). Producer and consumer
use plain `String` (de)serializers (Spring Boot defaults) and convert with the injected `JsonMapper` — no shared
serializer configuration is needed.

---

## 3. Ownership — who may edit what

| Role | Packages / files (only this role edits them) |
|---|---|
| **1. Ingest, delivery, infrastructure** | `ingest.*`, `delivery.*`, `common.config`, `common.web`, `common.persistence`; `pom.xml`, `application.yml`, `docker-compose.yml`, `backend/Dockerfile`, `infra/`; test package `support` |
| **2. Processing and alerts** | `rules.*` |
| **3. Conditions, runbooks, audit, tooling** | `common.condition`, `common.json`, `runbooks.*`, `dev`; `loadtest/`, `README.md`, `docs/` |
| **4. Frontend and demo** | `frontend/` (except `openapi.yaml`, see below), `demo/` |

Shared by agreement:

- **Repositories and entities** belong to the table owner (AGENTS.md §8), but any role may **call** any repository.
  Need a new query in someone else's repository? Ask the owner in chat — it is a two-line change for them.
  Until then, use an existing method or a query in your own package.
- **`frontend/openapi.yaml`** — each section belongs to the role that implements it (tags in the file). Change your
  section only, and announce it in chat **before** merging (Role 4 regenerates types).
- **`backend/src/test/resources/events/`** — anyone may **add** files; existing ones are changed only by Role 3.
- **`pom.xml`, `application.yml`** — need a dependency or property? Ask Role 1 in chat; Role 1 merges it quickly.
  Until it lands, keep the setting local (an annotation attribute, a `@Value` default) instead of editing the file.
- **Tests** live in the test package of your module (`rules`, `runbooks`, `ingest`, `delivery`, …).

---

## 4. Ground rules

1. **Branches.** One branch per piece of work: `role1/ingest-http`, `role2/processor`, … Merge to `main` in small
   pieces, at least every ~2 hours. `main` always builds, starts and passes `mvn test`.
2. **Migrations.** Never edit `V1`/`V2`. New migrations are named by timestamp so branches never collide:
   `V20261121_1430__rules_add_x.sql` (`spring.flyway.out-of-order: true` is already on). Only for your own tables.
3. **Contracts change only through their owner**: interface, API section, Kafka/Redis format, tables. Update
   `AGENTS.md` / `openapi.yaml` and post in chat **before** merging.
4. **Errors**: throw `NotFoundException` / `InvalidRequestException` (or a subclass) — never hand-build error JSON.
5. **Lists**: keyset only — `Cursors.decode(cursor)`, `Cursors.limit(limit)`, `CursorPage.of(window, Dto::from)`.
6. **Hot path**: no configuration reads from the database per event — only caches reloaded on `ConfigChangedEvent`.
7. **Code**: English comments and commit messages, Jackson 3 (`tools.jackson.*`), constructor injection, entities never
   leave the service layer (controllers return DTO records matching `openapi.yaml`).
8. **Build without local Java** (what CI-less step 0 used):
   ```bash
   cd backend
   docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
     -e TESTCONTAINERS_HOST_OVERRIDE=localhost -v m2cache:/root/.m2 -v "$PWD":/app -w /app \
     maven:3.9-eclipse-temurin-21 mvn -B test
   ```

---

## 5. Role 1 — Ingest, delivery and infrastructure

**Mission:** every event reaches `events.ingest`; every notification is delivered (or visibly retried) without loss;
everyone else has a running environment.

**Provides:** `OutboxService`, `ConfigChangedPublisher` (Redis), `RecentEvents` (Redis), Kafka topics, listener error
handling, REST for sources/teams/users/simulator/failed events, docker image with the UI.
**Consumes:** `ConditionCompiler` (validate team conditions), `TemplateRenderer` (message texts), `AuditService` (optional).

### Tasks, in order

1. **Kafka topics and listener error handling** (`common.config`)
   - `NewTopic` beans: `events.ingest` — 6 partitions, `retention.ms` 24 h; `events.ingest.DLT` — 1 partition, 7 days.
   - A `CommonErrorHandler` bean: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → `events.ingest.DLT`,
     back-off 1 s, 2 s, 5 s; connection errors to Redis/Postgres retried forever every 5 s (not DLT).
     Spring Boot applies this bean to the default listener factory, so Role 2's `@KafkaListener` picks it up untouched.
2. **HTTP ingest** (`ingest.api`, `ingest.source`) — `POST /api/ingest/{source}`
   - Source cache (`SourceRepository`, reload on `ConfigKinds.SOURCES`); unknown or inactive → 404.
   - Body must be valid JSON → else 400. Build `EventEnvelope.create(SourceType.HTTP, name, body, clock)`.
   - `kafkaTemplate.send(EVENTS_INGEST, sourceName, json).get(timeout)` → 202 `IngestAccepted`; failure/timeout → 503.
   - `recentEvents.add(body)`; update `fields:paths` / `card:{path}` (can come in task 6).
3. **CRUD** `/api/sources`, `/api/teams`, `/api/users` (`delivery.api`), DTOs exactly as in `openapi.yaml`.
   - Team `condition` validated with `conditionCompiler.compile(condition, strict)` (the stub accepts `==` only — fine).
   - After save/delete: `configChangedPublisher.publish(ConfigKinds.X)`. Duplicate name/email → 409.
4. **Redis config reload and recent events**: replace `LocalConfigChangedPublisher` (publish to channel
   `config.changed` after commit + `RedisMessageListenerContainer` that re-publishes `ConfigChangedEvent` locally) and
   `InMemoryRecentEvents` (`LPUSH` + `LTRIM 0 999`, `LRANGE`).
5. **Delivery** (`delivery.outbox`, `delivery.routing`, `delivery.provider.*`, `delivery.ratelimit`, `delivery.simulator`)
   - `OutboxService.enqueue`: user cache by team (reload on `USERS`/`TEAMS`); for each rule channel × active user pick
     the channel by `channelPreferences` / `workingHours` / `timezone`; render the text with `TemplateRenderer`; runbook
     link `/api/runbooks/{id}/file`; `UI` → one `OutboxTask.toTeamFeed` row.
   - `OutboxWorker` `@Scheduled(fixedDelay = 500)`: in a transaction `lockDueBatch(now, 100)`, send through a
     `NotificationProvider` per channel, then `markSent` / `scheduleRetry(error, now + 2|5|15|60 s)` / `markFailed`.
   - Providers: EMAIL via `JavaMailSender` → MailHog (:8025); SMS and CALL simulated; UI rows just become SENT.
   - Rate limit `rl:{user}:{channel}` 20/min; excess merged into one message.
   - Simulator: `GET /api/simulator/deliveries` (keyset), `GET /api/simulator/channels`,
     `POST /api/simulator/channels/{c}/toggle` (a down channel fails → retried), push every attempt to `/topic/simulator`.
6. **The rest**: external Kafka sources (`ingest.source` — one listener per active `kafka` source, topic from
   `config.topic`, rebuilt on `SOURCES`), DLT listener → `events` FAILED, `GET /api/events/failed`,
   `POST /api/events/{id}/replay`, `GET /api/ingest/lag`, `GET /api/fields`, hourly `deleteSentBefore(now − 1 day)`,
   UI build copied into `static/` by the Dockerfile.

### Verify without the others
- `curl` an event from `test/resources/events` → visible in `kafka-console-consumer --topic events.ingest`.
- Insert an alert + `delivery_outbox` rows with SQL → MailHog and the simulator show them; toggle SMS off and on → nothing lost.

### Done when
An event via HTTP **and** via an external Kafka topic arrives in `events.ingest`; with SMS switched off, messages are
delivered after switching it on, none lost; `docker compose up --build` runs everything including the UI.

---

## 6. Role 2 — Processing and alerts

**Mission:** turn the event stream into deduplicated, grouped alerts and push them live to the UI.

**Provides:** alerts and rules REST, `/topic/alerts/{teamId}`, `/topic/unrouted`, the `AlertCreator` transaction.
**Consumes:** `ConditionCompiler`, `JsonFieldLocator`, `TemplateRenderer`, `RunbookService`, `OutboxService`,
`AuditService`, `RecentEvents`, `EventRepository` (UNROUTED rows), repositories of your tables.

### Tasks, in order

1. **Processor** (`rules.processor`)
   - `@KafkaListener(topics = KafkaTopics.EVENTS_INGEST, groupId = KafkaTopics.PROCESSOR_GROUP, batch = "true")`
     taking `List<ConsumerRecord<String, String>>` + `Acknowledgment` (ack mode `manual` is already configured).
   - Parse each value into `EventEnvelope` with the injected `JsonMapper`. `SET seen:{event_id} NX EX 3600` — seen → skip.
   - A failing record → `throw new BatchListenerFailedException(msg, e, index)` (Role 1's error handler sends it to DLT).
   - `ack.acknowledge()` only **after** the transaction committed.
2. **Caches** (`rules.cache`): teams + active rules (`TeamRepository.findAllByOrderByIdAsc`,
   `RuleRepository.findAllByActiveTrueOrderByPriorityAscIdAsc`), conditions compiled once with
   `conditionCompiler.compile(condition, team.isStrict())`; reload on `TEAMS` / `RULES`.
3. **Matching, fingerprint, dedup** (`rules.dedup`)
   - Every matching team (multi-match) → its matching rules in priority order (all fire).
   - Fingerprint `sha256(ruleId + values of group_by_paths [+ window number for per_window])` via `JsonFieldLocator`.
   - `SET dedup:{fp} pending NX EX window`: new → create; exists → `INCR cnt:{fp}`, `SADD dirty:fp {fp}`.
4. **`AlertCreator`** (`rules.alert`), one transaction: title via `TemplateRenderer` (default: rule name) →
   `alertRepository.insertIfNoOpenAlert(Alert.forRule(...))` (empty → it's a repeat) → `findById` →
   `runbookService.attach` → `outboxService.enqueue` → `auditService.log` → `SET dedup:{fp} {id} XX KEEPTTL`.
5. **Counter flush** every 1 s: `SPOP dirty:fp 500`, skip `pending`, `GETDEL cnt:{fp}` →
   `alertRepository.addOccurrences(deltas)`; push changed alerts.
6. **Push** (`rules.push`): `SimpMessagingTemplate.convertAndSend(WebSocketTopics.alerts(teamId), AlertSummary)` at most
   once per second per alert (`SET ws:{id} NX PX 1000`).
7. **Auto-close** by `resolve_condition` (`findOpenByFingerprint` → `resolve` → audit `AUTO_CLOSE` → push).
   **UNROUTED** (`rules.unrouted`): no team matched → `Alert.unrouted(...)` with fingerprint
   `sha256("unrouted:" + source + sorted top-level keys)`, `Event.unrouted(...)` via `EventRepository`, `/topic/unrouted`.
8. **REST** (`rules.api`, `rules.alert`): rules CRUD (validate conditions; publish `RULES`), `POST /api/rules/dry-run`
   over `recentEvents.latest(1000)`, `GET /api/alerts` (keyset: `findByTeamId…` / `findByTeamIsNull…`),
   `GET /api/alerts/{id}`, `POST …/ack` and `…/resolve` (audit `ACK`/`RESOLVE`, push), hourly `deleteResolvedBefore(now − 7 days)`.

### Verify without the others
- Produce envelopes straight into Kafka (`kafka-console-producer` or a test) — no ingest controller needed.
- Teams and rules via SQL; conditions with `==` work on the stub.
- Integration test: `@SpringBootTest` + `@Import({PostgresTestConfiguration, KafkaTestConfiguration, RedisTestConfiguration})`;
  event into the topic → row in `alerts`.

### Done when
500 identical events → one alert with `occurrencesCount = 500`; Team Rates and Team Pricing rules work; editing a
rule takes effect without restart; the UI feed updates live.

---

## 7. Role 3 — Condition engine, runbooks, audit, tooling

**Mission:** the engine that decides what matters (merge it **first** — everybody's stubs get replaced by it),
runbooks attached to alerts, the audit trail, and the tools to test and present the system.

**Provides:** `ConditionCompiler`, `JsonFieldLocator`, `TemplateRenderer`, `RunbookService`, `AuditService`,
runbooks REST, audit REST, generator, load test, README.
**Consumes:** `ConfigChangedPublisher`, repositories of your tables, `AlertRepository` (read).

### Tasks, in order

1. **Condition engine** (`common.condition`, `common.json`) — target: merged within the first ~3 hours.
   - Fill `DefaultConditionCompiler`: `and` / `or` / leaf; all operators of AGENTS.md §5; regex/glob compiled once to
     `Pattern`; missing field → every comparison false (including `!=`, `not_in`), `exists` false.
   - Flexible mode: exact path, else key with the same last segment anywhere (case-insensitive); numeric strings compare
     as numbers. Strict mode: exact path and exact types. Fill `ExactPathFieldLocator` accordingly (rename if you like).
   - Validation messages name the node: `Unknown operator 'equals' at $.and[1]`.
   - Pure unit tests (no Spring, no DB) over `test/resources/events/*.json`; keep `DefaultConditionCompilerTest` green.
2. **Audit** (`runbooks.audit`, `runbooks.api`): `DefaultAuditService` inserts `AuditLog` (joins the caller's
   transaction); `GET /api/alerts/{id}/audit` (404 for an unknown alert).
3. **Runbooks REST** (`runbooks.api`): multipart upload (`file`, `teamId?`, `condition?` JSON string, 10 MB), list via
   `RunbookRepository` summaries (never `content`), `GET/PUT/DELETE /api/runbooks/{id}`,
   `GET /api/runbooks/{id}/file` with `MediaTypeFactory.getMediaType(fileName)` and `Content-Disposition: inline`.
   Validate conditions; publish `RUNBOOKS`.
4. **Selection** (`runbooks.cache`, `runbooks.matching`): cache of `RunbookSummary` + compiled conditions (reload on
   `RUNBOOKS` / `TEAMS`); `DefaultRunbookService.attach` in the order of AGENTS.md §6 step 8, then
   `alert.attachRunbook(...)` and audit `RUNBOOK_ATTACHED`. No queries per event.
5. **Generator** (`dev`): `POST /api/dev/generate` sends events through the real HTTP ingest (`RestClient` to
   `/api/ingest/{source}`) at the requested rate — exercises the whole pipeline.
6. **Load test** (`loadtest/`): k6 script — 500 identical events per minute + a mix of the sample events; README section
   on how to run it and what to expect (one alert, counter 500).
7. **README.md**: run locally, add a source / team / rule / runbook, the demo script with the "foreign" schema.

### Verify without the others
- Engine: unit tests only. Runbooks: `curl` upload/download. `attach`: unit test with an `Alert` built in the test.

### Done when
All operators and both modes are covered by tests; a judge uploads a runbook in any format with a condition and the
next matching alert links to the file.

---

## 8. Role 4 — Frontend and demo

**Mission:** an operator can run the whole demo with the mouse — no curl, no Postman.

**Provides:** the React app in `frontend/`, the demo script and slides in `demo/`.
**Consumes:** `frontend/openapi.yaml` only (REST + WebSocket). No polling — live data comes from STOMP.

### Setup (first hour)
- Vite + React + TypeScript in `frontend/` (the folders under `src/` already exist).
- Types from the contract: `npx openapi-typescript openapi.yaml -o src/api/schema.ts`; regenerate when the file changes.
- Mocks: MSW handlers for every path in `openapi.yaml`, plus a fake STOMP source that emits an `AlertSummary` every
  2 s. `VITE_USE_MOCKS=true|false` switches between mocks and the backend — one variable, nothing else.
- Dev proxy in `vite.config.ts`: `/api` and `/ws` (with `ws: true`) → `http://localhost:8080`.
- STOMP client `@stomp/stompjs` over plain WebSocket at `/ws` (no SockJS).
- `npm run build` output is copied into `backend/src/main/resources/static` by Role 1's Docker build — keep the build
  self-contained (`base: '/'`, no environment-specific URLs).

### Pages, in order
1. **Feed** (`pages/feed`): team picker, alerts newest first, severity colour, status, live counter, ack/resolve
   buttons; infinite scroll with `nextCursor`; updates from `/topic/alerts/{teamId}`.
2. **Alert card** (`pages/alert`): `samplePayload` as a JSON tree, runbook link (`fileUrl`), audit history.
3. **Unrouted** (`pages/unrouted`): `/api/alerts?unrouted=true` + `/topic/unrouted`; button "create rule from this
   event" → opens the rule editor prefilled with paths/values from the event.
4. **Condition builder** (shared component): and/or tree, field autocompletion from `/api/fields`, operator list from
   the `Operator` enum, live preview via `POST /api/rules/dry-run`; shows the 400 `detail` next to the bad node.
5. **Rules** (`pages/rules`), **Teams & users** (`pages/recipients` — preferences and working hours editors),
   **Runbooks** (`pages/runbooks` — upload with condition), **Failed events** (`pages/failed` — replay),
   **Simulator** (`pages/simulator` — channel switches, live deliveries from `/topic/simulator`).
6. **Demo** (`demo/`): step-by-step script — new source with a foreign schema, team + rule built in the UI, runbook upload,
   burst of events → one alert with a counter, SMS outage and recovery — plus slides.

### Verify without the others
Everything runs on mocks; switching `VITE_USE_MOCKS=false` against a running backend is the integration test.

### Done when
The demo script runs end to end with the mouse only.

---

## 9. Integration checkpoints

| When | What must work on `main` |
|---|---|
| ~3 h | Real condition engine (Role 3) merged; HTTP ingest → `events.ingest` (Role 1); processor creates alerts from Kafka (Role 2); UI feed on mocks (Role 4) |
| ~6 h | Event via `curl` → alert → UI row in `delivery_outbox` → live in the UI feed against the real backend |
| ~9 h | Emails in MailHog, simulator retries; runbook attached and downloadable from the alert card; rules editable in the UI without restart |
| final | Full demo script by all four, twice in a row |

## 10. Open questions (decide in chat, then update AGENTS.md)

1. `teams.condition = null` — "no events" (current) or "all events, rules filter"? Affects Role 2's matching and the
   UI's team editor.
2. A `not` node in conditions — Role 3 decides; it would touch `openapi.yaml` (`Condition`) and the builder (Role 4).
3. A user in several teams — not supported (one `users.team_id`); changing it needs a migration by Role 1.
