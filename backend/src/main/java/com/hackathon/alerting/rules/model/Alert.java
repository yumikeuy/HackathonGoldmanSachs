package com.hackathon.alerting.rules.model;

import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.runbooks.model.Runbook;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * An alert (table {@code alerts}): one group of events with the same fingerprint.
 *
 * <p>Creation in the hot path normally goes through a native
 * {@code INSERT … ON CONFLICT DO NOTHING RETURNING id} against the partial unique index
 * {@code uq_alerts_open_fingerprint} (at most one OPEN/ACKED alert per fingerprint).
 * {@code occurrences_count} and {@code last_seen_at} are bumped in bulk by the counter flusher,
 * so an entity loaded earlier may hold stale values for them.
 *
 * <p>Two shapes, both enforced by a CHECK constraint:
 * <ul>
 *   <li>rule-based — {@link #forRule}: has a rule and team, severity comes from the rule;</li>
 *   <li>unrouted — {@link #unrouted}: no rule and no team, severity {@link Severity#UNKNOWN}.</li>
 * </ul>
 */
@Entity
@Table(name = "alerts")
public class Alert {

    /** Length of {@code alerts.title}; longer rendered titles are truncated. */
    public static final int TITLE_MAX_LENGTH = 500;
    /** Length of {@code alerts.fingerprint}: SHA-256 in hex. */
    public static final int FINGERPRINT_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} for UNROUTED alerts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", updatable = false)
    private Rule rule;

    /** {@code null} for UNROUTED alerts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", updatable = false)
    private Team team;

    /** Attached runbook; {@code null} if none matched or the runbook was deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "runbook_id")
    private Runbook runbook;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = FINGERPRINT_LENGTH, columnDefinition = "char(64)")
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "occurrences_count", nullable = false)
    private int occurrencesCount = 1;

    /** The first event of the group, as received. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_payload", nullable = false, updatable = false)
    private JsonNode samplePayload;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Alert() {
        // for JPA
    }

    private Alert(Rule rule, Team team, Severity severity, String fingerprint, String title,
                  JsonNode samplePayload, Instant seenAt) {
        this.rule = rule;
        this.team = team;
        this.severity = Objects.requireNonNull(severity, "severity");
        this.fingerprint = requireFingerprint(fingerprint);
        this.title = truncateTitle(Objects.requireNonNull(title, "title"));
        this.samplePayload = Objects.requireNonNull(samplePayload, "samplePayload");
        this.firstSeenAt = Objects.requireNonNull(seenAt, "seenAt");
        this.lastSeenAt = seenAt;
    }

    /**
     * A new alert produced by a rule. Team and severity are taken from the rule.
     *
     * @param rule        the matched rule
     * @param fingerprint SHA-256 hex of rule id + group-by values (+ window number)
     * @param title       rendered title; truncated to {@link #TITLE_MAX_LENGTH}
     * @param firstEvent  the event that opened the alert
     * @param seenAt      the event's {@code ingested_at}
     */
    public static Alert forRule(Rule rule, String fingerprint, String title, JsonNode firstEvent, Instant seenAt) {
        Objects.requireNonNull(rule, "rule");
        return new Alert(rule, rule.getTeam(), rule.getAssignedSeverity(), fingerprint, title, firstEvent, seenAt);
    }

    /** A new alert for an event no team matched: no rule, no team, severity UNKNOWN. */
    public static Alert unrouted(String fingerprint, String title, JsonNode event, Instant seenAt) {
        return new Alert(null, null, Severity.UNKNOWN, fingerprint, title, event, seenAt);
    }

    /**
     * Acknowledge the alert (OPEN → ACKED). Idempotent for an already acknowledged alert.
     *
     * @return {@code true} if the status changed
     * @throws IllegalStateException if the alert is already resolved
     */
    public boolean acknowledge() {
        return switch (status) {
            case OPEN -> {
                status = AlertStatus.ACKED;
                yield true;
            }
            case ACKED -> false;
            case RESOLVED -> throw new IllegalStateException("Alert " + id + " is already resolved");
        };
    }

    /**
     * Resolve the alert, manually or by {@code resolve_condition}. Idempotent.
     *
     * @return {@code true} if the status changed
     */
    public boolean resolve(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status == AlertStatus.RESOLVED) {
            return false;
        }
        status = AlertStatus.RESOLVED;
        resolvedAt = at;
        return true;
    }

    /** Whether the alert is still collecting repeats (OPEN or ACKED). */
    public boolean isOpen() {
        return status.isOpen();
    }

    /** Whether this is an UNROUTED alert (no team matched the event). */
    public boolean isUnrouted() {
        return rule == null;
    }

    /** Attach the runbook chosen by {@code RunbookService.attach} (or re-chosen by {@code RunbookResync}). */
    public void attachRunbook(Runbook runbook) {
        this.runbook = Objects.requireNonNull(runbook, "runbook");
    }

    /** Remove the runbook link: no runbook applies any more ({@code RunbookResync}). */
    public void detachRunbook() {
        this.runbook = null;
    }

    public Long getId() {
        return id;
    }

    public Rule getRule() {
        return rule;
    }

    public Team getTeam() {
        return team;
    }

    public Runbook getRunbook() {
        return runbook;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Severity getSeverity() {
        return severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public int getOccurrencesCount() {
        return occurrencesCount;
    }

    public JsonNode getSamplePayload() {
        return samplePayload;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    private static String requireFingerprint(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.length() != FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException("fingerprint must be " + FINGERPRINT_LENGTH + " hex chars, got " + fingerprint.length());
        }
        return fingerprint;
    }

    private static String truncateTitle(String title) {
        if (title.length() <= TITLE_MAX_LENGTH) {
            return title;
        }
        return title.substring(0, TITLE_MAX_LENGTH - 1) + "…";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Alert other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Alert.class.hashCode();
    }

    @Override
    public String toString() {
        return "Alert{id=" + id + ", status=" + status + ", severity=" + severity + ", fingerprint=" + fingerprint + '}';
    }
}
