# Event processing and alerts

> Owner: **Role 2**. Code: `backend/src/main/java/com/hackathon/alerting/rules` (design notes in `rules/package-info.java`).

How an event that arrived on `events.ingest` becomes an alert in the UI.

## The pipeline

```
events.ingest ─▶ EventBatchListener ──▶ EventRouter ──┬─ no team matched ─▶ UNROUTED alert (severity UNKNOWN)
                 batch, 1 tx per record     │          └─ per team, per rule (priority order)
                 seen:{event_id}            │               ├─ resolve_condition ─▶ resolve the open alert
                                            │               └─ condition ─▶ fingerprint ─▶ first of group? ─┬─ yes ─▶ create alert
                                            │                                                               └─ no ──▶ count a repeat
                                            └─ after commit: WebSocket push to /topic/alerts/{teamId} or /topic/unrouted
```

1. **Read a batch** from `events.ingest`. Each record is processed and committed on its own; the batch offset is
   acknowledged only after all of them. A failing record goes back to the error handler (retries, then the DLT).
2. **Drop redeliveries**: `SET seen:{event_id} NX` (1 h). If processing fails, the mark is removed so the retry runs.
3. **Find teams** whose condition matches — an event may belong to several teams. A team without a condition owns
   no events. No team → **UNROUTED**.
4. **Run the team's active rules** in priority order; every matching rule fires. An event matching a rule's
   `resolve_condition` resolves that group's open alert instead of firing the rule.
5. **Fingerprint** = SHA-256 of the rule id + the values of `group_by_paths` (+ the window number for `per_window`).
   Same fingerprint = same alert.
6. **Deduplicate** in two layers:
   - Redis decides cheaply who creates the alert: `SET dedup:{fp} pending NX EX <window>`;
   - Postgres guarantees it: `INSERT … ON CONFLICT DO NOTHING` against the unique index on open fingerprints.
     If Redis lost its keys, the insert conflicts and the event becomes a repeat — never a duplicate alert.
7. **Create the alert** in one transaction: insert → attach a runbook (Role 3) → queue notifications (Role 1) →
   audit `ALERT_CREATED`. Then push it to the UI.
8. **Repeats** are counted in Redis (`INCR cnt:{fp}`) and added to Postgres in one batch **every second**;
   the updated alert is pushed at most once per second.

The configuration (teams, rules, compiled conditions) lives in memory and is reloaded on every change — no database
reads per event. Saving a rule through the API takes effect within milliseconds, without a restart.

## Grouping

| `group_mode` | One alert per … | A new alert opens when … |
|---|---|---|
| `while_open` (default) | fingerprint, while the alert is OPEN or ACKED | the alert was resolved |
| `per_window` | fingerprint and dedup window (`dedup_window_sec`, epoch-aligned) | the next window starts |

`group_by_paths` decides what "the same" means, e.g. `["instrument"]` → one alert per instrument. Empty → one alert
per rule.

## Alert lifecycle

`OPEN → ACKED → RESOLVED` or `OPEN → RESOLVED`. Ack and resolve are idempotent; ack after resolve is 409.
Resolved by a user (`POST /api/alerts/{id}/resolve`) or by a rule's `resolve_condition` (audit `AUTO_CLOSE`).
After resolution the next matching event opens a new alert. Resolved alerts are deleted after 7 days.

Audit actions written by processing: `ALERT_CREATED`, `ACK`, `RESOLVE`, `AUTO_CLOSE` (Role 3 adds `RUNBOOK_ATTACHED`,
and `RUNBOOK_DETACHED` when an open alert's runbook is re-selected after runbooks change — see [runbooks.md](runbooks.md)).

## Unrouted events

No team matched → an alert with severity `UNKNOWN` and no team, one per source and top-level shape of the event
(`sha256("unrouted:" + source + sorted top-level keys)`), plus a row in `events` for every such event. They appear on
`/topic/unrouted` and in `GET /api/alerts?unrouted=true`.

**Team suggestions** — `GET /api/alerts/{id}/team-suggestions` ranks the teams such an event probably belongs to:

- **near misses**: the share of a team's condition leaves the event satisfies, each leaf checked on its own
  ("1 of 2 conditions match");
- **source history**: which teams the source's other events were routed to (Redis hash `src:teams:{source}`).

`score = 0.7 × conditions + 0.3 × history` (conditions only when the source has no history); top 5.

## Live feed

| Destination | Body | When |
|---|---|---|
| `/topic/alerts/{teamId}` | `AlertSummary` | created, acked, resolved — immediately; counter updates — at most once per second |
| `/topic/unrouted` | `AlertSummary` | same, for alerts without a team |

Pushes happen only after the change committed, so the UI never shows something that was rolled back.

## Metrics (`/actuator/prometheus`)

| Meter | Type | Tags |
|---|---|---|
| `alerting_events_processed_total` | counter | `outcome` = routed / unrouted / duplicate |
| `alerting_alerts_created_total` | counter | `severity` |
| `alerting_alerts_repeats_total` | counter | — |
| `alerting_alerts_resolved_total` | counter | `by` = user / rule |
| `alerting_processing_delay_seconds` | timer (p50/p95/p99) | — time from `ingested_at` until processed |

## Redis keys used by processing

`seen:{event_id}`, `dedup:{fp}`, `cnt:{fp}`, `dirty:fp`, `ws:{alert_id}` (AGENTS.md §3) and `src:teams:{source}`
(hash team id → routed events, no TTL) for team suggestions.

## Known limits

- `last_seen_at` of a counted repeat is the flush time — up to one second late.
- For `per_window` rules, `resolve_condition` only finds the alert of the current window.
- A team that matches an event but has no matching rule produces nothing (the event is not UNROUTED).
