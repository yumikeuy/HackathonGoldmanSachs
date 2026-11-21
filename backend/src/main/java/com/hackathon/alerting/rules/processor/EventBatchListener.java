package com.hackathon.alerting.rules.processor;

import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.rules.dedup.SeenEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Consumer of {@code events.ingest} (AGENTS.md §3, §6).
 *
 * <ul>
 *   <li>Batch listener; concurrency is bounded by the topic's partitions ({@code spring.kafka.listener.concurrency}).</li>
 *   <li>Every record is processed and committed to Postgres on its own; the batch is acknowledged only after all
 *       of them, so an offset is never committed before the database changes of its record.</li>
 *   <li>A failing record is reported as {@link BatchListenerFailedException} with its index: the container's error
 *       handler (Role 1: retries, then {@code events.ingest.DLT}) commits the records before it and retries from it.</li>
 *   <li>Redeliveries are skipped by {@link SeenEvents}; the mark is removed if processing fails, so the retry runs.</li>
 * </ul>
 */
@Component
public class EventBatchListener {

    /** Listener container id, e.g. for {@code KafkaListenerEndpointRegistry#getListenerContainer}. */
    public static final String LISTENER_ID = "event-processor";

    private final JsonMapper json;
    private final SeenEvents seen;
    private final EventRouter router;

    public EventBatchListener(JsonMapper json, SeenEvents seen, EventRouter router) {
        this.json = json;
        this.seen = seen;
        this.router = router;
    }

    @KafkaListener(id = LISTENER_ID, topics = KafkaTopics.EVENTS_INGEST, groupId = KafkaTopics.PROCESSOR_GROUP, batch = "true")
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, String> record = records.get(i);
            try {
                process(record);
            } catch (RuntimeException e) {
                throw new BatchListenerFailedException("Failed to process " + record.topic() + "-" + record.partition()
                        + "@" + record.offset(), e, i);
            }
        }
        ack.acknowledge();
    }

    private void process(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = json.readValue(record.value(), EventEnvelope.class);
        if (!seen.markSeen(envelope.eventId())) {
            return;
        }
        try {
            router.route(envelope);
        } catch (RuntimeException e) {
            seen.forget(envelope.eventId());
            throw e;
        }
    }
}
