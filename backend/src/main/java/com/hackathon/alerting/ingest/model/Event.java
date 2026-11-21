package com.hackathon.alerting.ingest.model;

import com.hackathon.alerting.rules.model.Alert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A persisted event (table {@code events}): only unrouted ({@link EventStatus#UNROUTED})
 * and failed ({@link EventStatus#FAILED}) ones. The full event stream is never written to the database.
 *
 * <p>The primary key is the envelope's {@code event_id}, assigned by the application. That is why the entity
 * implements {@link Persistable}: Spring Data calls {@code persist} without an extra SELECT,
 * and saving the same event twice fails on the PK instead of silently turning into an UPDATE.
 */
@Entity
@Table(name = "events")
public class Event implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "source_name", nullable = false, updatable = false, length = 100)
    private String sourceName;

    /** Time the server accepted the event; this is also the event time. */
    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    /** The original event as received, any schema. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, updatable = false)
    private JsonNode rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private EventStatus status;

    /** Error message for FAILED events. */
    @Column(name = "error")
    private String error;

    /** UNROUTED alert created for this event; set to NULL when the alert is purged. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id")
    private Alert alert;

    @Transient
    private boolean isNew = true;

    protected Event() {
        // for JPA
    }

    private Event(UUID eventId, String sourceName, Instant ingestedAt, JsonNode rawPayload, EventStatus status) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.ingestedAt = Objects.requireNonNull(ingestedAt, "ingestedAt");
        this.rawPayload = Objects.requireNonNull(rawPayload, "rawPayload");
        this.status = status;
    }

    /** An event no team matched; {@code alert} is the UNROUTED alert created for it. */
    public static Event unrouted(UUID eventId, String sourceName, Instant ingestedAt, JsonNode rawPayload, Alert alert) {
        Event event = new Event(eventId, sourceName, ingestedAt, rawPayload, EventStatus.UNROUTED);
        event.alert = alert;
        return event;
    }

    /** An event whose processing failed. */
    public static Event failed(UUID eventId, String sourceName, Instant ingestedAt, JsonNode rawPayload, String error) {
        Event event = new Event(eventId, sourceName, ingestedAt, rawPayload, EventStatus.FAILED);
        event.error = error;
        return event;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSourceName() {
        return sourceName;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public JsonNode getRawPayload() {
        return rawPayload;
    }

    public EventStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Alert getAlert() {
        return alert;
    }

    /** Replay of a FAILED event failed again: update the error message. */
    public void recordFailure(String error) {
        this.status = EventStatus.FAILED;
        this.error = error;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Event other)) {
            return false;
        }
        return eventId != null && eventId.equals(other.getEventId());
    }

    @Override
    public int hashCode() {
        return Event.class.hashCode();
    }

    @Override
    public String toString() {
        return "Event{eventId=" + eventId + ", sourceName='" + sourceName + "', status=" + status + '}';
    }
}
