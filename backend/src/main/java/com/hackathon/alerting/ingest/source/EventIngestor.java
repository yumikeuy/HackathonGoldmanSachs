package com.hackathon.alerting.ingest.source;

import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.common.web.ServiceUnavailableException;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.fields.FieldCatalog;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.recent.RecentEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Puts an event into {@code events.ingest}: wraps it in an {@link EventEnvelope} (server time, new id) and
 * waits for Kafka's acknowledgement ({@code acks=all}). Only then the event counts as accepted; afterwards it is
 * remembered in {@link RecentEvents} and the {@link FieldCatalog}. Used by HTTP, external Kafka and file sources.
 *
 * <p>Metrics: {@value #ACCEPTED} (tag {@code source_type}) and {@value #REJECTED} (tag {@code reason}; this class counts
 * {@code kafka_unavailable}, the HTTP controller counts {@code invalid_json} and {@code unknown_source}).
 */
@Service
public class EventIngestor {

    public static final String ACCEPTED = "alerting.ingest.accepted";
    public static final String REJECTED = "alerting.ingest.rejected";

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Clock clock;
    private final RecentEvents recentEvents;
    private final FieldCatalog fieldCatalog;
    private final Duration sendTimeout;
    private final MeterRegistry metrics;
    private final Counter kafkaUnavailable;

    public EventIngestor(KafkaTemplate<String, String> kafka, JsonMapper json, Clock clock, RecentEvents recentEvents,
                         FieldCatalog fieldCatalog, @Value("${alerting.ingest.send-timeout:5s}") Duration sendTimeout,
                         MeterRegistry metrics) {
        this.kafka = kafka;
        this.json = json;
        this.clock = clock;
        this.recentEvents = recentEvents;
        this.fieldCatalog = fieldCatalog;
        this.sendTimeout = sendTimeout;
        this.metrics = metrics;
        this.kafkaUnavailable = rejected(metrics, "kafka_unavailable");
    }

    /** Counter of events refused at ingest for {@code reason}. */
    public static Counter rejected(MeterRegistry metrics, String reason) {
        return Counter.builder(REJECTED).description("Events refused at ingest").tag("reason", reason).register(metrics);
    }

    /**
     * @return the envelope Kafka acknowledged
     * @throws ServiceUnavailableException if Kafka did not acknowledge within the timeout (HTTP 503)
     */
    public EventEnvelope ingest(SourceType type, String sourceName, JsonNode payload) {
        EventEnvelope envelope = EventEnvelope.create(type, sourceName, payload, clock);
        send(envelope);
        metrics.counter(ACCEPTED, "source_type", type.code()).increment();
        recentEvents.add(payload);
        fieldCatalog.record(payload);
        return envelope;
    }

    /** Sends a ready envelope (e.g. a replay) and waits for the acknowledgement. */
    public void send(EventEnvelope envelope) {
        try {
            kafka.send(KafkaTopics.EVENTS_INGEST, envelope.sourceName(), json.writeValueAsString(envelope))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kafkaUnavailable.increment();
            throw new ServiceUnavailableException("Interrupted while waiting for Kafka", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            kafkaUnavailable.increment();
            throw new ServiceUnavailableException("Kafka is unavailable, retry later", e);
        }
    }
}
