/**
 * Rules and alerts — event processing, deduplication, alert lifecycle and the live feed. Owner: Role 2.
 *
 * <h2>Pipeline (AGENTS.md §6)</h2>
 * <pre>
 * events.ingest ─▶ processor.EventBatchListener   batch, one transaction per record, ack after all commits
 *                    │  dedup.SeenEvents          seen:{event_id} — drop redeliveries
 *                    ▼
 *                  processor.EventRouter          cache.RoutingCache: teams + rules, compiled once
 *                    ├─ no team matched ─────────▶ unrouted.UnroutedEventHandler (UNKNOWN alert, events row)
 *                    └─ per team, per rule (priority order)
 *                         ├─ resolve_condition ─▶ alert.AlertService#autoResolve
 *                         └─ condition ─────────▶ dedup.FingerprintCalculator → dedup.DedupStore (Redis)
 *                                                   ├─ first of group ─▶ alert.AlertCreator (one transaction:
 *                                                   │                    insert → runbook → outbox → audit)
 *                                                   └─ repeat ─────────▶ cnt:{fp} ─▶ dedup.OccurrenceFlusher (1 s)
 *                  push.AlertPublisher ◀── after commit: /topic/alerts/{teamId}, /topic/unrouted
 * REST: api.RuleController (/api/rules, dry-run), alert.AlertController (/api/alerts)
 * </pre>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Transactions vs side effects.</b> Database writes run in a transaction; Redis bookkeeping and WebSocket
 *       pushes run only after it committed. A rollback never leaves the UI or Redis ahead of Postgres.</li>
 *   <li><b>Deduplication in two layers.</b> Redis ({@code SET dedup:{fp} pending NX}) decides cheaply who creates the
 *       alert; the partial unique index on open fingerprints ({@code INSERT … ON CONFLICT DO NOTHING}) guarantees it.
 *       If Redis loses keys, the conflicting insert is turned into a repeat of the open alert — never a duplicate.</li>
 *   <li><b>Counting.</b> The first event sets {@code occurrences_count = 1}; repeats are {@code INCR}ed in Redis and
 *       added in one JDBC batch per second. {@code last_seen_at} of a flushed alert is the flush time (≤ 1 s late).</li>
 *   <li><b>Grouping.</b> {@code while_open}: one alert per fingerprint while it is OPEN/ACKED, enforced by the index even
 *       after the Redis window expires. {@code per_window}: the window number is part of the fingerprint.</li>
 *   <li><b>Routing semantics.</b> An event may match several teams; all matching rules of a team fire. A team with
 *       {@code condition = null} owns no events (AGENTS.md §11, q.1). A team that matches but has no matching rule
 *       produces nothing — the event is not UNROUTED. If an event matches a rule's {@code resolve_condition}, it resolves
 *       the group's open alert and does not also fire the rule.</li>
 *   <li><b>Resilience of the hot path.</b> The routing table is an immutable snapshot swapped on reload; a stored
 *       condition that no longer compiles disables only that rule (logged), and a throwing predicate is "no match".</li>
 *   <li><b>Redeliveries.</b> {@code seen:{event_id}} makes processing effectively once within an hour; the mark is
 *       removed when processing fails, so the retry triggered by the error handler really runs.</li>
 * </ul>
 *
 * <h2>Contracts used</h2>
 * {@code ConditionCompiler}, {@code JsonFieldLocator}, {@code TemplateRenderer}, {@code RunbookService},
 * {@code AuditService} (Role 3); {@code OutboxService}, {@code ConfigChangedPublisher}, {@code RecentEvents},
 * {@code EventRepository} (Role 1). The audit action {@code ALERT_CREATED} is written when an alert opens, in
 * addition to {@code ACK}, {@code RESOLVE} and {@code AUTO_CLOSE}.
 */
package com.hackathon.alerting.rules;
