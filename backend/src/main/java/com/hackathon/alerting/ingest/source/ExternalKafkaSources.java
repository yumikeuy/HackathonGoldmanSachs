package com.hackathon.alerting.ingest.source;

import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.source.SourceCache.CachedSource;
import com.hackathon.alerting.ingest.source.SourceCache.SourcesReloaded;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads external Kafka topics of active {@code kafka} sources ({@code sources.config.topic}) and forwards every
 * message to {@code events.ingest} through {@link EventIngestor}.
 *
 * <p>One listener container per source, consumer group {@code alerting-source-{name}}. Containers are
 * rebuilt whenever the source list is reloaded: added sources start, removed/deactivated ones stop, a changed
 * topic restarts. A message is committed only after {@code events.ingest} acknowledged it; if Kafka refuses,
 * the message is retried every 5 s, so nothing is lost. A value that is not JSON is ingested as a JSON string.
 */
@Component
public class ExternalKafkaSources implements SmartLifecycle {

    static final String GROUP_PREFIX = "alerting-source-";

    private static final Logger log = LoggerFactory.getLogger(ExternalKafkaSources.class);

    private final ConsumerFactory<String, String> consumerFactory;
    private final EventIngestor ingestor;
    private final JsonMapper json;
    private final Map<String, Running> running = new HashMap<>();
    private volatile boolean started;

    @SuppressWarnings("unchecked")
    public ExternalKafkaSources(ConsumerFactory<?, ?> consumerFactory, EventIngestor ingestor, JsonMapper json) {
        this.consumerFactory = (ConsumerFactory<String, String>) consumerFactory;
        this.ingestor = ingestor;
        this.json = json;
    }

    @EventListener
    public synchronized void onSourcesReloaded(SourcesReloaded event) {
        if (!started) {
            return;
        }
        Map<String, String> wanted = new HashMap<>();
        for (CachedSource source : event.sources()) {
            if (source.active() && source.type() == SourceType.KAFKA) {
                source.topic().ifPresentOrElse(
                        topic -> wanted.put(source.name(), topic),
                        () -> log.warn("Kafka source '{}' has no config.topic, not listening", source.name()));
            }
        }
        running.entrySet().removeIf(entry -> {
            if (!Objects.equals(wanted.get(entry.getKey()), entry.getValue().topic())) {
                entry.getValue().container().stop();
                log.info("Stopped listening to Kafka source '{}' (topic {})", entry.getKey(), entry.getValue().topic());
                return true;
            }
            return false;
        });
        wanted.forEach((name, topic) -> running.computeIfAbsent(name, n -> startContainer(n, topic)));
    }

    private Running startContainer(String sourceName, String topic) {
        ContainerProperties properties = new ContainerProperties(topic);
        properties.setGroupId(GROUP_PREFIX + sourceName);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener((MessageListener<String, String>) record -> forward(sourceName, record));

        ConcurrentMessageListenerContainer<String, String> container = new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
        container.setBeanName("external-source-" + sourceName);
        container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        container.start();
        log.info("Listening to Kafka source '{}' on topic {}", sourceName, topic);
        return new Running(topic, container);
    }

    void forward(String sourceName, ConsumerRecord<String, String> record) {
        ingestor.ingest(SourceType.KAFKA, sourceName, parse(record.value()));
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return json.readTree(value);
        } catch (JacksonException e) {
            return JsonNodeFactory.instance.stringNode(value);
        }
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void stop() {
        started = false;
        running.values().forEach(r -> r.container().stop());
        running.clear();
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    private record Running(String topic, ConcurrentMessageListenerContainer<String, String> container) {
    }
}
