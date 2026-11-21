package com.hackathon.alerting.ingest.dlt;

import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.Event;
import com.hackathon.alerting.ingest.repository.EventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

/**
 * Stores every envelope that landed in {@code events.ingest.DLT} as a FAILED row of table {@code events}, with the
 * exception message from the DLT headers, so it shows up in {@code GET /api/events/failed} and can be replayed.
 *
 * <p>Idempotent by {@code event_id}. Uses its own container factory (record ack, retry every 5 s on any error,
 * never re-publishing to a DLT) instead of the processor's batch / manual-ack factory.
 */
@Component
public class DeadLetterListener {

    static final String CONTAINER_FACTORY = "deadLetterContainerFactory";
    static final String GROUP_ID = "alerting-dlt";
    static final int MAX_ERROR_LENGTH = 4000;

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final EventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    public DeadLetterListener(EventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(topics = KafkaTopics.EVENTS_INGEST_DLT, groupId = GROUP_ID, containerFactory = CONTAINER_FACTORY)
    @Transactional
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String error = truncate(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
        Event event = toFailedEvent(record, error);
        if (events.existsById(event.getEventId())) {
            return;
        }
        events.save(event);
        log.warn("Event {} from '{}' failed processing: {}", event.getEventId(), event.getSourceName(), error);
    }

    private Event toFailedEvent(ConsumerRecord<String, String> record, String error) {
        try {
            EventEnvelope envelope = json.readValue(record.value(), EventEnvelope.class);
            return Event.failed(envelope.eventId(), envelope.sourceName(), envelope.ingestedAt(), envelope.rawPayload(), error);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // Not an envelope at all: keep the raw text so nothing is lost.
            String source = record.key() == null ? "unknown" : record.key();
            String value = record.value() == null ? "" : record.value();
            UUID id = UUID.nameUUIDFromBytes((record.topic() + "/" + record.partition() + "/" + record.offset()).getBytes(StandardCharsets.UTF_8));
            return Event.failed(id, source, clock.instant(), JsonNodeFactory.instance.stringNode(value),
                    "Malformed envelope: " + e.getMessage() + (error == null ? "" : "; " + error));
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    @Bean(CONTAINER_FACTORY)
    @SuppressWarnings("unchecked")
    static ConcurrentKafkaListenerContainerFactory<String, String> deadLetterContainerFactory(ConsumerFactory<?, ?> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory((ConsumerFactory<String, String>) consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
