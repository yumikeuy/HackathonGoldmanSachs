# Roles — phase 2: from working pipeline to demo-ready product

Phase 1 built the pipeline: events of any shape come in over HTTP and Kafka, are routed by conditions, deduplicated into
alerts, delivered by email/SMS/call/UI and shown live. Phase 2 finishes the backend for the demo: **runbooks**,
the **event generator and load test**, the **file source**, **seed data, smoke test and documentation**, metrics and
the **team-suggestion** bonus.

Three backend roles work in parallel, each **in the packages they already own** — the numbering is the same as in phase 1,
so nobody moves into someone else's code. The frontend is built by its own person (Role 4) against `frontend/openapi.yaml`.

`AGENTS.md` stays the source of truth for the domain; if it and this file disagree, `AGENTS.md` wins — fix this file.
Phase 1's split is in git history (`git show 36d19b8:roles.md`).

---

## 1. State at the start of phase 2 (`main`, 95 tests green)

| Area | Status |
|---|---|
| Ingest: HTTP, external Kafka, DLT + replay, lag, field catalog, recent events | done (Role 1) |
| Sources / teams / users CRUD, config reload over Redis | done (Role 1) |
| Delivery: routing by preferences and hours, outbox worker, retries, rate limit, simulator | done (Role 1) |
| Processing: routing, fingerprint, dedup, grouping, auto-close, UNROUTED, live push, rules + alerts API | done (Role 2) |
| Condition engine (all operators, strict / flexible), audit trail + `GET /api/alerts/{id}/audit` | done (Role 3) |
| **Runbooks API** (CRUD, multipart upload, file download) | written on branch `role3/runbooks-api` — **needs a test fix before merging** (§8 task 0) |
| **Runbooks**: cache, selection (`RunbookService.attach` is still a no-op) | **phase 2 — Role 3** |
| **Generator** `POST /api/dev/generate`, **k6 load test** | **phase 2 — Role 2** (moved from Role 3) |
| **File source** (tail a log file), **seed data**, **smoke test**, **README / docs** | **phase 2 — Role 1** |
| Metrics, team suggestions for unrouted alerts (bonus), hardening | phase 2 — every role in its area |
| Frontend | Role 4, in progress separately |

## 2. Prepared for phase 2 (already on this branch — nobody needs to touch shared files to start)

| What | Why it prevents conflicts |
|---|---|
| `openapi.yaml`: `GET /api/alerts/{id}/team-suggestions` + schema `TeamSuggestion` | Role 2 implements it, Role 4 builds the UI for it now — same contract |
| `openapi.yaml`: config format of `file` sources (`{"path": …}`) | Role 1 implements, Role 4 shows the form field |
| `openapi.yaml`: `x-planned: true` on the operations not implemented yet — the generator and team suggestions (the runbooks API is written, so it carries none) | each role removes **its own** markers when it ships; Role 1's contract test checks "implemented ⇔ not planned" — no shared allow-list to edit |
| `application.yml`: `alerting.dev.generator-enabled` (`DEV_GENERATOR_ENABLED`, default `true`) | Role 2 needs the switch, Role 1 owns the file |
| `README.md` (Role 1) + one file per role in `docs/` | everyone documents only their own file |
| `backend/src/test/resources/runbooks/` — sample runbooks (`.md`, `.txt`, `.yaml`) | used by Role 3's tests and Role 1's seed script |
| `dev` package now owned by Role 2 | generator moves from Role 3 to Role 2 |

---

## 3. Ownership — who may edit what

| Role | Packages / files (only this role edits them) |
|---|---|
| **1. Ingest, delivery, platform** | `ingest.*`, `delivery.*`, `common.config`, `common.web`, `common.persistence`; `pom.xml`, `application.yml`, `docker-compose.yml`, `backend/Dockerfile`, `.dockerignore`, `infra/`; `README.md`, `docs/ingest-delivery.md`, `docs/operations.md`; tests `support/*`, `AlertingApplicationTest`, `ingest/*`, `delivery/*`, `common/config`, `common/web` |
| **2. Processing, generator, load** | `rules.*`, `dev`; `backend/src/main/resources/dev/`; `loadtest/`; `docs/processing.md`, `docs/load-testing.md`; tests `rules/*`, `dev/*` |
| **3. Runbooks and conditions** | `runbooks.*`, `common.condition`, `common.json`; `docs/runbooks.md`, `docs/conditions.md`; tests `runbooks/*`, `common/condition`, `common/json`; `test/resources/runbooks/*` and `test/resources/events/*` (others may **add** files) |
| **4. Frontend and demo** (separate) | `frontend/` except `openapi.yaml`, `demo/` |

**`frontend/openapi.yaml`** — each tag belongs to the role that implements it: `ingest`, `config`, `simulator` → Role 1;
`rules`, `alerts` (except `/audit`), `dev` → Role 2; `runbooks`, `/api/alerts/{id}/audit`, the `Condition` schemas → Role 3.
Edit only your part; announce changes in chat **before** merging (Role 4 regenerates types).

**`AGENTS.md`** (local, not in git) — each role updates the sections of its area (§3 Redis keys, §4 schema, §5 conditions,
§12 work log) and shares the file in chat.

## 4. What each role uses from the others (all of it exists today)

| Used by | Provided by | What |
|---|---|---|
| Role 2 (generator) | Role 1 | `ingest.source.EventIngestor.ingest(SourceType, sourceName, payload)` — the real ingest path: Kafka with `acks=all`, recent events, field catalog; throws `ServiceUnavailableException` (503) when Kafka is down. `ingest.source.SourceCache.findActive(name)` — is the source active |
| Role 2 (alert creation) | Role 3 | `RunbookService.attach(alert, event)` — already called inside `AlertCreator`; it starts attaching once Role 3 ships, no change on Role 2's side |
| Role 2 (suggestions) | Role 3 | `ConditionCompiler`, `JsonFieldLocator` |
| Role 1 (notifications) | Role 3 | the runbook link `{public-url}/api/runbooks/{id}/file` — `MessageComposer` already writes it when `alert.runbook` is set |
| Role 1 (seed script) | Role 3 | `POST /api/runbooks` (multipart) — over HTTP, by the contract in `openapi.yaml` |
| Role 3 (cache reload) | Role 1 | `ConfigChangedPublisher.publish(ConfigKinds.RUNBOOKS)`, `ConfigChangedEvent` |
| everyone | Role 1 | `Clock` bean (`common.config.ClockConfig`), errors `NotFoundException` 404, `InvalidRequestException` 400, `ConflictException` 409, `ServiceUnavailableException` 503; `CursorPage` / `Cursors` for lists; test configs `PostgresTestConfiguration`, `KafkaTestConfiguration`, `RedisTestConfiguration`, `@JpaRepositoryTest`, `MutableClock` |

## 5. Ground rules (unchanged from phase 1)

1. **Branches**: `role1/…`, `role2/…`, `role3/…`; merge to `main` in small PRs, at least every ~2 hours; `main` always
   builds, starts and passes `mvn test`. **Before opening a PR, merge the current `main` into your branch and run
   `mvn test`** — git reports no conflict when another role removed or renamed a class you use, only the compiler does
   (this happened to `role3/runbooks-api`, see §8 task 0).
2. **Migrations**: never edit existing ones; new ones are timestamped — `V20261121_1430__runbooks_add_x.sql` — and only for
   your own tables.
3. **Need something in another role's file** (a repository method, a property, a dependency)? Ask the owner in chat.
4. **Time**: inject `Clock`, never `Instant.now()`. **Errors**: throw the `common.web` exceptions. **Lists**: keyset only.
5. **Hot path**: no configuration reads from the database per event — caches reloaded on `ConfigChangedEvent`.
6. **Metrics**: Micrometer (`MeterRegistry`), names `alerting.<area>.<what>` (lowercase, dots), tags for low-cardinality
   values only (channel, severity, outcome — never ids or source names of unbounded sets).
7. **Code**: English comments and commit messages; Jackson 3 (`tools.jackson.*`); constructor injection; DTO records in the
   API, never entities; every public class documented.
8. **Build** without local Java:
   ```bash
   cd backend && docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
     -e TESTCONTAINERS_HOST_OVERRIDE=localhost -v m2cache:/root/.m2 -v "$PWD":/app -w /app \
     maven:3.9-eclipse-temurin-21 mvn -B test
   ```

---

## 6. Role 1 — Ingest, delivery and platform

**Mission:** a fresh clone becomes a running, seeded, documented demo with one command; a third source type works;
the API matches its contract.

### Tasks, in order

1. **File source** (`ingest.source`) — AGENTS.md §2.1 bonus, `SourceType.FILE` already exists.
   - A `FileTailSources` component like `ExternalKafkaSources`: listens to `SourceCache.SourcesReloaded`, keeps one tailer
     per active `file` source, stops tailers of removed/deactivated ones.
   - Tailer: starts at the **end** of the file (only new lines), polls every 500 ms, survives rotation and truncation
     (file shrank or was replaced → reopen from the start), reads complete lines only.
   - Each line → `EventIngestor.ingest(SourceType.FILE, name, payload)`; a JSON line as is, any other line as
     `{"message": "<line>"}` (contract in `openapi.yaml`). Kafka down (`ServiceUnavailableException`) → retry the
     **same** line later, never skip it.
   - `docker-compose.yml`: mount `./infra/logs` to `/data/logs` in the backend container.
   - Test: temp file, append lines, rotate it — events arrive in `events.ingest` in order, none lost.
2. **Seed data** (`infra/seed/`): `seed.sh` (bash + curl, no jq) and JSON bodies next to it. Creates over the REST API:
   - sources `payments-monitoring` (http), `workflow-events` (kafka, topic `workflow.events`), `legacy-gateway` (http),
     `app-log` (file, `/data/logs/app.log`);
   - teams **Team Rates**, **Team Pricing**, **Team Infra** with conditions matching the sample events in
     `test/resources/events`; users with different channel preferences and working hours (emails land in MailHog);
   - rules: rejected trades grouped by `instrument`, failed workflows with `resolve_condition` status `OK`, high CPU
     `per_window`; runbooks uploaded from `test/resources/runbooks` (works once Role 3's API is merged; before that the
     step prints a warning and continues).
   - Idempotent: a second run changes nothing (409 / existing name → skip).
3. **Smoke test** (`infra/smoke.sh`): against `docker compose up --build` + seed — send each sample event, append a line to
   `infra/logs/app.log`, then assert via the API: alerts exist with the right team and severity, an email is in MailHog
   (`GET :8025/api/v2/messages`), a repeat increases `occurrencesCount`. Exit code ≠ 0 on any failure.
   Fix what breaks in your packages; report other roles' bugs in chat with the failing step.
4. **API contract test** (`support`): build the app context, read `/v3/api-docs` (springdoc) and `frontend/openapi.yaml`,
   compare **paths + methods**: every operation without `x-planned` must be implemented, every `x-planned` one must not be,
   and nothing may be implemented that is not in the contract. Failure message lists the differences.
5. **Metrics** in your packages: `alerting.ingest.accepted` / `alerting.ingest.rejected` (tag `reason`),
   `alerting.delivery.sent` / `.failed` / `.retried` (tag `channel`), gauge `alerting.delivery.pending`.
6. **Docs**: `README.md` (what it is, quick start, links), `docs/ingest-delivery.md`, `docs/operations.md`
   (configuration properties, seed, smoke, metrics, troubleshooting). Keep `docker-compose.yml` and the README in sync.

### Done when
Fresh clone → `docker compose up --build` → `infra/seed/seed.sh` → `infra/smoke.sh` passes; the contract test is green;
a line appended to the log file becomes an alert.

---

## 7. Role 2 — Processing, generator and load

**Mission:** prove the pipeline under load (500 identical events per minute → one alert counted 500), give the demo a
traffic generator, and make processing observable.

### Tasks, in order

0. **Fix the flaky end-to-end test first** (it guards everybody's merges). Since config reloads go through Redis Pub/Sub,
   they are asynchronous: `AlertPipelineIntegrationTest` creates a rule (or team) and sends events immediately, so an event
   can be routed before `RoutingCache` reloaded — `ruleCreatedThroughTheApiTakesEffectWithoutRestart` timed out once in
   review. After every configuration change the test must wait until the routing table contains the new rule/team
   (e.g. `await().until(() -> routingCache.current()…)`) before sending events. Same for the `rule(…)` / `@BeforeEach` helpers.
1. **Generator** (`dev`) — `POST /api/dev/generate`, schemas `GenerateRequest` / `GenerateAccepted`.
   - The controller exists only when `alerting.dev.generator-enabled=true` (`@ConditionalOnProperty`); otherwise 404.
   - Unknown or inactive `source` (`SourceCache.findActive`) → 400. Answers 202 at once; generation runs in the background
     at `ratePerSecond` on a dedicated scheduler (cap concurrent generations, e.g. 4; reject more with 409).
   - Each event goes through `EventIngestor.ingest(source.type(), name, payload)` — the real path. Stop a run on
     `ServiceUnavailableException` and log it.
   - Without `template`: cycle through built-in samples copied to `src/main/resources/dev/samples/` (test resources are
     not on the runtime classpath). With `template`: define and document simple placeholders for variation in string
     values, e.g. `{{seq}}`, `{{uuid}}`, `{{randInt:1:100}}`, `{{oneOf:EUR|USD}}`; describe them in the `GenerateRequest`
     schema (your section of `openapi.yaml`).
   - Remove the `x-planned` marker of the operation in the same PR.
2. **k6 load test** (`loadtest/`), run with `docker run --network host -i grafana/k6 run - < loadtest/<script>.js`.
   - `setup()` creates its own source, team and rule over the REST API (unique names), so the script works on any database.
   - `burst.js`: 500 identical events within one minute → `GET /api/alerts?teamId=` returns exactly one alert with
     `occurrencesCount = 500` (poll a few seconds); thresholds fail the run otherwise.
   - `mixed.js`: the sample events from `loadtest/payloads/` at a configurable rate; thresholds on ingest p95 latency and
     error rate.
   - Record results (machine, rates, latencies, lag from `GET /api/ingest/lag`) in `docs/load-testing.md`.
3. **Metrics** in `rules`: `alerting.events.processed` (tag `outcome`: `routed` / `unrouted` / `duplicate`),
   `alerting.alerts.created` (tag `severity`), `alerting.alerts.repeats`, `alerting.alerts.resolved` (tag `by`: `user` /
   `rule`), timer `alerting.processing.delay` (now − `ingested_at`).
4. **Team suggestions** (bonus, `rules.unrouted`) — `GET /api/alerts/{id}/team-suggestions`, schema `TeamSuggestion`.
   - Score each team from the routing cache by the share of its condition leaves the alert's `sample_payload` satisfies
     (compile leaves one by one with `ConditionCompiler`), blended with **source history**: count which teams events of a
     source were routed to in a new Redis hash `src:teams:{source}` (`HINCRBY` in the router; add the key to AGENTS.md §3).
     The source of an unrouted alert is in its `events` rows (`EventRepository`, by `alert_id`).
   - At most 5, best first, `reason` human-readable; empty list for alerts that have a team; 404 for unknown alerts.
   - Remove the `x-planned` marker in the same PR.
5. **Hardening** of `rules`: inject `Clock` instead of `Instant.now()` (`AlertService`, `AlertCleanup`,
   `OccurrenceFlusher`); extend `AlertPipelineIntegrationTest` with audit assertions (`ALERT_CREATED`, `AUTO_CLOSE`, `ACK`,
   `RESOLVE`) and, after Role 3's merge, a runbook-attached assertion; optionally auto-close `per_window` alerts of earlier
   windows.
6. **Docs**: `docs/processing.md` (from `rules/package-info.java`, for humans), `docs/load-testing.md`.

### Done when
The generator drives the demo from the UI; `burst.js` passes on the compose stack; metrics are visible in
`/actuator/prometheus`; suggestions rank the right team first for `alien-schema.json` once a matching team exists.

---

## 8. Role 3 — Runbooks and conditions

**Mission:** AGENTS.md §2.6 — a judge uploads a runbook in any format with a condition, and the next matching alert links
to it in the UI card, the email and the audit trail.

### Tasks, in order

0. **Finish `role3/runbooks-api` and merge it** — the API itself is done and matches the contract (CRUD, multipart upload
   with file-name cleaning and condition validation, download with `Content-Type` from the extension, `RUNBOOKS`
   reload). The branch was cut from `main` before Role 1's merge, so it does not compile against today's `main`:
   - merge the current `main` into the branch;
   - `RunbookCrudServiceTest` imports `LocalConfigChangedPublisher`, which Role 1 replaced by the Redis publisher. Replace it
     with a small test `ConfigChangedPublisher` that publishes `ConfigChangedEvent` after commit (≈ 15 lines; verified in
     review: all 9 runbooks tests then pass);
   - add a test for an upload over 10 MB → 413 `application/problem+json` (not covered yet);
   - optional: also strip Windows paths (`C:\x\file.pdf`) from file names — `StringUtils.getFilename` handles `/` only;
   - `mvn test` green, then merge. The runbooks operations in `openapi.yaml` carry no `x-planned` marker any more.
1. **Runbook cache** (`runbooks.cache`): `RunbookRepository.findAllSummaries()` (never `content`) + teams' strict flags
   (`TeamRepository`); compile each condition once (team runbook → the team's `strict`, global → flexible). Load before Kafka
   consumers start (`SmartInitializingSingleton`, like `RoutingCache`); reload on `RUNBOOKS` and `TEAMS`. A stored condition
   that no longer compiles → skip that runbook and log, never fail the reload.
2. **Selection** — fill `DefaultRunbookService.attach(alert, event)` (AGENTS.md §6 step 8):
   1. `alert.getRule().getOverrideRunbook()` if set; 2. the alert team's runbooks whose condition matches;
   3. global runbooks whose condition matches; ties → lower id; runbooks with `condition = null` match only via override.
   Then `alert.attachRunbook(runbookRepository.getReferenceById(id))` and audit `RUNBOOK_ATTACHED`
   (actor `AuditLog.SYSTEM_ACTOR`, details `{runbookId, fileName, reason: "override" | "team" | "global"}`).
   Runs inside Role 2's creation transaction — cache only, no queries except the reference; never throw for "no match".
3. ~~**REST**~~ — done on `role3/runbooks-api` (task 0). For reference, what it covers (`runbooks.api`), schemas `Runbook`, `RunbookUpload`, `RunbookUpdate`:
   - `GET /api/runbooks?teamId=` — that team's plus global runbooks (summaries, never bytes); without `teamId` all.
   - `POST` multipart: `file` required and non-empty; file name from `getOriginalFilename()` stripped of any path, ≤ 255
     chars; `teamId` must exist (400); `condition` is a JSON string → parse (400) and validate with `ConditionCompiler`
     (400 with the engine's detail). 201 with `fileUrl = /api/runbooks/{id}/file`. Over 10 MB → 413 problem+json.
   - `GET /{id}`, `PUT /{id}` (team, condition, optional new file → `replaceFile`), `DELETE /{id}` → 204 (rules and alerts
     lose the link by `ON DELETE SET NULL`).
   - `GET /{id}/file`: `RunbookRepository.findFileById`; `Content-Type` from
     `MediaTypeFactory.getMediaType(fileName)` (fallback `application/octet-stream`); `Content-Disposition: inline` with the
     UTF-8 file name (`ContentDisposition` builder).
   - Publish `ConfigKinds.RUNBOOKS` after every change.
4. **Condition engine follow-ups** (from the phase-1 review):
   - reject unknown keys in leaves and in `and` / `or` nodes — a typo like `"vaule"` must be a 400, not a silent default;
   - document `exists: false` and "JSON null counts as absent" in `docs/conditions.md`, AGENTS.md §5 and the `LeafCondition`
     description in `openapi.yaml`;
   - decide AGENTS.md §11 q.2 (`not` node); if added, update the `Condition` schema and tell Role 4;
   - `PlaceholderTemplateRenderer`: remove the step-0 stub note (it is final), add tests for flexible placeholders;
   - move `AuditEntry` out of `runbooks.api` into `runbooks.audit` (the service should not depend on the web layer);
   - optional rename `ExactPathFieldLocator` → `DefaultJsonFieldLocator` (it does the flexible search too). Two tests of
     Role 2 construct it (`RoutingCacheTest`, `FingerprintCalculatorTest`): announce in chat and change those two lines in
     the same PR — the one agreed exception to the ownership rule.
5. **Docs**: `docs/conditions.md` (every operator with an example on the sample events), `docs/runbooks.md`.

### Done when
Upload `trade-rejection.md` with its condition → send `rates-trade-rejected.json` → the alert card shows the runbook, the
email contains the download link, the audit shows `RUNBOOK_ATTACHED`, and the file downloads with `Content-Type: text/markdown`.

---

## 9. Role 4 — Frontend (separate) — what changes for you

- **New endpoint** `GET /api/alerts/{id}/team-suggestions` for the unrouted page (`TeamSuggestion`).
- **File sources**: the source form needs a `path` field for `type = file` (see `SourceInput.config`).
- **`x-planned: true`** marks endpoints that are not live yet — keep their mocks; the marker disappears when the backend
  ships them. The **runbooks API** is live as soon as `role3/runbooks-api` is merged.
- The generator (`POST /api/dev/generate`) can back a "send test events" button on the simulator page.

---

## 10. Integration checkpoints

| When | On `main` |
|---|---|
| now | `role3/runbooks-api` fixed and merged (Role 3); flaky end-to-end test fixed (Role 2); then this branch |
| ~3 h | runbook cache + selection (Role 3); generator (Role 2); file source (Role 1) |
| ~6 h | seed + smoke pass on the compose stack; `burst.js` passes; contract test green with no `x-planned` left except bonuses |
| final | demo dry run: fresh clone → compose → seed → UI scenario, twice in a row |

## 11. Open decisions

| Question | Decides | Default if nobody decides |
|---|---|---|
| `not` node in conditions (AGENTS.md §11 q.2) | Role 3 | not added |
| `teams.condition = null` (AGENTS.md §11 q.1) | team | stays "no events" (implemented in routing and dry-run) |
| Generator placeholders syntax | Role 2 | as proposed in §7 |
| File source starts at end or beginning of the file | Role 1 | end (only new lines) |
