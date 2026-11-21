# Ingest, sources and delivery

> Owner: **Role 1**. How events get in (HTTP, Kafka, file) and how notifications get out (channels, routing, retries,
> rate limit, simulator). Packages: `ingest.*`, `delivery.*`, `common.config`.

## Sources

A source is a row of `sources` (`/api/sources`). Its `name` is how events are attributed; any event shape is accepted —
the payload is stored as is (`raw_payload`), nothing is parsed against a schema.

| `type` | `config` | How events arrive |
|---|---|---|
| `http` | `{}` | `POST /api/ingest/{name}` with any JSON body |
| `kafka` | `{"topic": "workflow.events"}` | the service consumes the external topic (group `alerting-source-{name}`) |
| `file` | `{"path": "/data/logs/app.log"}` | the service tails the file (path inside the backend container) |

Creating, changing or deactivating a source takes effect on all instances within a second (Redis `config.changed`),
no restart. Validation: a `kafka` source needs `config.topic`, a `file` source `config.path`; names are unique (409).

### HTTP

`POST /api/ingest/{source}`

| Answer | When |
|---|---|
| **202** `{"eventId", "ingestedAt"}` | Kafka acknowledged the event (`acks=all`) — it will be processed |
| 400 | the body is not JSON |
| 404 | unknown or inactive source |
| 503 | Kafka did not acknowledge within `alerting.ingest.send-timeout` (5 s) — retry later |

The server stamps `ingested_at`; that is the event time.

### Kafka

One listener container per active `kafka` source. A message is committed only after it was forwarded to
`events.ingest`; if that fails it is retried every 5 s, so nothing is lost. A value that is not JSON is ingested as a
JSON string.

### File

One tailer per active `file` source (`ingest.source.FileTailSources` / `FileTailer`), polling every 500 ms:

- starts at the **end** of an existing file — only new lines; a file that appears later is read from the start;
- only complete lines are ingested; a half-written last line waits;
- **rotation** (path now points to a new file): the old file is read to its end first, then the new one from the start;
  **truncation** (file shrank): reading restarts at the beginning;
- a line that is a JSON object/array is ingested as is, any other line as `{"message": "<line>"}`;
- Kafka down → the **same** line is retried on the next poll; lines are never skipped.

In docker-compose `./infra/logs` is mounted at `/data/logs`; the seeded source `app-log` tails `infra/logs/app.log`.

## Internal Kafka topics

| Topic | Partitions | Retention | Content |
|---|---|---|---|
| `events.ingest` | 6 | 24 h | envelope `{event_id, ingested_at, source_type, source_name, raw_payload}`, key = source name |
| `events.ingest.DLT` | 1 | 7 days | envelopes whose processing failed |

Error handling of the processor's listener (`common.config.KafkaConfig`): a failing record is retried after 1, 2 and
5 s, then published to the DLT. If Postgres or Redis is unreachable (anywhere in the cause chain) the batch is retried
every 5 s forever instead — the data is fine, the infrastructure is not.

The DLT listener stores each dead letter as a FAILED row of `events` with the error message:

- `GET /api/events/failed` — newest first, keyset pages;
- `POST /api/events/{eventId}/replay` — sends the payload again (new `event_id`, original `ingested_at`) and deletes the
  FAILED row; if it fails again a new row appears.

`GET /api/ingest/lag` — how far the processor (group `alerting-processor`) is behind, per partition.

## Recent events and field catalog (Redis)

Every accepted event also goes to:

- `recent:events` — the last 1000 payloads, for rule dry-runs and previews;
- `fields:paths` (path → JSON type) and `card:{path}` (HyperLogLog, 24 h) — `GET /api/fields` for condition
  autocompletion. A field is **not suggested** if its name looks like an ID or a timestamp (`id`, `tradeId`,
  `created_at`, `timestamp`…), if it has more than 1000 distinct values in 24 h, or if a strict team or its rules use it.

Redis is not required for ingestion: if it is down, events still go to Kafka; only previews miss them.

## Teams and users

`/api/teams` — name, `condition` (validated, 400 with the reason), `strict`. A team with users, rules or alerts cannot
be deleted (409).

`/api/users` — one team each; `email` (unique, 409), `phone`, `timezone` (IANA, default `Europe/Warsaw`),
`channelPreferences` in priority order, `workingHours`:

```json
{ "teamId": 1, "name": "Anna Kowalska", "email": "anna@bank.example", "phone": "+48 600 100 001",
  "timezone": "Europe/Warsaw",
  "channelPreferences": [ {"channel": "SMS", "hours": "08-20"}, {"channel": "EMAIL"} ],
  "workingHours": {"from": "08:00", "to": "18:00", "days": [1, 2, 3, 4, 5]} }
```

## Choosing the channel

For a new alert the rule's `channels` decide *what kind* of notification is wanted; each user's preferences decide
*how* they get it (`delivery.routing.ChannelRouter`):

- `UI` in the rule → one row for the team feed (no user).
- Personal channels (EMAIL / SMS / CALL) → **at most one** message per active user: the first of the user's preferences
  that the rule allows, that the user can be reached on (email address / phone) and that applies **now** in the
  user's time zone:
  - a preference with `hours` (`"08-20"`, end exclusive; `"22-06"` wraps midnight) applies inside that window — also
    outside working hours;
  - a preference without `hours` applies only during working hours (no `workingHours` = always working; a night shift
    `22:00–06:00` belongs to the day it started).
- A user without preferences gets the rule's first suitable channel during working hours.
- Nothing applies → no personal message; the alert still reaches the team through the UI feed.

Messages are rendered when queued (`delivery_outbox.payload`): subject, text, and the runbook as a **link**
`{alerting.public-url}/api/runbooks/{id}/file`, never an attachment.

## Delivery: outbox worker

`delivery_outbox` rows are written in the alert's transaction and sent by `OutboxWorker` every 500 ms:

1. lock up to 100 due PENDING rows (`FOR UPDATE SKIP LOCKED` — several instances can run);
2. send through the channel's provider: EMAIL → SMTP (MailHog), SMS / CALL → simulated (logged), UI → recorded;
3. success → SENT; failure → retry after **2, 5, 15, 60 s**, then FAILED;
4. push every attempt to `/topic/simulator`.

**Rate limit**: at most 20 messages per user and channel per minute (Redis `rl:{user}:{channel}`). If more are due, the
last permit is spent on one **digest** ("6 alerts (merged)") covering all the excess; with no permits left the rows wait
for the window to reset (no retry counted). If Redis is down the limit is not enforced.

Hourly cleanup deletes SENT rows older than one day.

## Delivery simulator

| | |
|---|---|
| `GET /api/simulator/deliveries` | all outbox rows, newest first (status, retries, last error, rendered payload) |
| `GET /api/simulator/channels` | which channels are up |
| `POST /api/simulator/channels/{EMAIL\|SMS\|CALL\|UI}/toggle` | switch a channel down/up |
| WebSocket `/topic/simulator` | every attempt, live |

While a channel is down its deliveries fail and are retried; switch it back on within the retry schedule (~80 s) and
every message is delivered — none lost. Switches are per instance and reset on restart.

## Metrics

| Metric | Tags | Meaning |
|---|---|---|
| `alerting.ingest.accepted` | `source_type` | events acknowledged by Kafka |
| `alerting.ingest.rejected` | `reason` = `invalid_json` / `unknown_source` / `kafka_unavailable` | events refused |
| `alerting.delivery.sent` | `channel` | notifications delivered |
| `alerting.delivery.retried` | `channel` | failed attempts scheduled for retry |
| `alerting.delivery.failed` | `channel` | notifications given up after all retries |
| `alerting.delivery.pending` | — | gauge: PENDING outbox rows |

Exposed at `/actuator/prometheus` (names with `_` and `_total`).
