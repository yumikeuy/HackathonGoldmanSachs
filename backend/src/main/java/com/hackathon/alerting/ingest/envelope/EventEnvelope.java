package com.hackathon.alerting.ingest.envelope;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hackathon.alerting.ingest.model.SourceType;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Message on {@code events.ingest} (AGENTS.md §3). Produced by Role 1 (HTTP and external Kafka sources),
 * consumed by Role 2's processor. Serialized as snake_case JSON:
 * <pre>{@code
 * { "event_id": "uuid", "ingested_at": "2026-11-21T00:17:03.120Z",
 *   "source_type": "http", "source_name": "payments-monitoring", "raw_payload": { } }
 * }</pre>
 *
 * @param eventId    unique id assigned on ingest; the processor deduplicates redeliveries by it
 * @param ingestedAt server time of ingestion; this is the event time
 * @param sourceType how the event arrived
 * @param sourceName {@code sources.name}; also the Kafka message key
 * @param rawPayload the event exactly as received, any schema
 */
public record EventEnvelope(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("ingested_at") Instant ingestedAt,
        @JsonProperty("source_type") SourceType sourceType,
        @JsonProperty("source_name") String sourceName,
        @JsonProperty("raw_payload") JsonNode rawPayload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(rawPayload, "rawPayload");
    }

    /** A new envelope with a random id, stamped with the current time of {@code clock}. */
    public static EventEnvelope create(SourceType sourceType, String sourceName, JsonNode rawPayload, Clock clock) {
        return new EventEnvelope(UUID.randomUUID(), clock.instant(), sourceType, sourceName, rawPayload);
    }
}
