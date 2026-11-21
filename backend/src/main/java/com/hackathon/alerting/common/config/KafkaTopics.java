package com.hackathon.alerting.common.config;

/**
 * Internal Kafka topics (AGENTS.md §3). Topic creation is Role 1's {@code NewTopic} configuration.
 *
 * <p>Message format on {@link #EVENTS_INGEST}: key = {@code source_name} (String), value = UTF-8 JSON of
 * {@link com.hackathon.alerting.ingest.envelope.EventEnvelope}. Both sides use plain String
 * (de)serializers and convert with the application's {@code JsonMapper}.
 */
public final class KafkaTopics {

    /** Envelopes from all sources; 6 partitions, keyed by source name, 24 h retention. */
    public static final String EVENTS_INGEST = "events.ingest";
    /** Envelopes whose processing failed after retries; 1 partition, 7 days retention. */
    public static final String EVENTS_INGEST_DLT = "events.ingest.DLT";
    /** Consumer group of the event processor (matches {@code spring.kafka.consumer.group-id}). */
    public static final String PROCESSOR_GROUP = "alerting-processor";

    private KafkaTopics() {
    }
}
