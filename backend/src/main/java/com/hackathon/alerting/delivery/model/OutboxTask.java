package com.hackathon.alerting.delivery.model;

import com.hackathon.alerting.rules.model.Alert;
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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * A single notification in the delivery queue (table {@code delivery_outbox}).
 *
 * <p>Created in the same transaction as the alert ({@code OutboxService.enqueue}) and sent by
 * {@code OutboxWorker} with retries. For {@link Channel#UI} the recipient is the team feed
 * ({@code user_id = NULL}); every other channel targets a specific user. The invariant is enforced
 * by a database CHECK constraint and by the factory methods here.
 *
 * <p>IDs come from a sequence stepping by 50 (pooled optimizer) so that Hibernate can insert
 * rows in JDBC batches — see V2__constraints_and_indexes.sql.
 */
@Entity
@Table(name = "delivery_outbox")
public class OutboxTask {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_outbox_id_gen")
    @SequenceGenerator(name = "delivery_outbox_id_gen", sequenceName = "delivery_outbox_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false, updatable = false)
    private Alert alert;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false, updatable = false)
    private Team team;

    /** Recipient; {@code null} means the team's UI feed only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 10)
    private Channel channel;

    /** Message already rendered for the channel (subject/text/runbook link). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OutboxStatus status = OutboxStatus.PENDING;

    /** Number of failed attempts so far. */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** The worker will not pick the row up before this moment. */
    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxTask() {
        // for JPA
    }

    private OutboxTask(Alert alert, Team team, User user, Channel channel, JsonNode payload) {
        this.alert = Objects.requireNonNull(alert, "alert");
        this.team = Objects.requireNonNull(team, "team");
        this.user = user;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.nextRetryAt = Instant.now();
    }

    /**
     * A notification to a user over a personal channel (EMAIL / SMS / CALL).
     * The row's team is the user's team.
     *
     * @throws IllegalArgumentException if the channel is {@link Channel#UI}
     */
    public static OutboxTask toUser(Alert alert, User user, Channel channel, JsonNode payload) {
        Objects.requireNonNull(user, "user");
        if (!Objects.requireNonNull(channel, "channel").isPersonal()) {
            throw new IllegalArgumentException("Channel " + channel + " is not addressed to a user; use toTeamFeed()");
        }
        return new OutboxTask(alert, user.getTeam(), user, channel, payload);
    }

    /** The single UI-feed row for a team ({@code user_id = NULL}). */
    public static OutboxTask toTeamFeed(Alert alert, Team team, JsonNode payload) {
        return new OutboxTask(alert, team, null, Channel.UI, payload);
    }

    /** The provider confirmed delivery. */
    public void markSent(Instant at) {
        requirePending();
        this.status = OutboxStatus.SENT;
        this.sentAt = Objects.requireNonNull(at, "at");
        this.lastError = null;
    }

    /**
     * A failed attempt that will be retried. The retry schedule (2, 5, 15, 60 s) is owned by the worker.
     *
     * @param error         failure reason
     * @param nextAttemptAt when to try again
     */
    public void scheduleRetry(String error, Instant nextAttemptAt) {
        requirePending();
        this.retryCount++;
        this.lastError = error;
        this.nextRetryAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }

    /** Retries exhausted — permanent failure. */
    public void markFailed(String error) {
        requirePending();
        this.retryCount++;
        this.status = OutboxStatus.FAILED;
        this.lastError = error;
    }

    private void requirePending() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException("Outbox task " + id + " is already " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Alert getAlert() {
        return alert;
    }

    public Team getTeam() {
        return team;
    }

    public User getUser() {
        return user;
    }

    public Channel getChannel() {
        return channel;
    }

    public JsonNode getPayload() {
        return payload;
    }

    /** Replace the message, e.g. when merging messages above the {@code rl:} rate limit. */
    public void setPayload(JsonNode payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutboxTask other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return OutboxTask.class.hashCode();
    }

    @Override
    public String toString() {
        return "OutboxTask{id=" + id + ", channel=" + channel + ", status=" + status + ", retryCount=" + retryCount + '}';
    }
}
