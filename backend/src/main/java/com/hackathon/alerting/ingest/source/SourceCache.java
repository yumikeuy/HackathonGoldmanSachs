package com.hackathon.alerting.ingest.source;

import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.ingest.model.Source;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.repository.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All sources in memory, so ingestion never reads configuration from the database (AGENTS.md §10).
 * Reloaded at startup and on {@link ConfigKinds#SOURCES}; after each reload a {@link SourcesReloaded} event
 * is published for components that react to source changes (external Kafka listeners).
 */
@Component
public class SourceCache {

    private static final Logger log = LoggerFactory.getLogger(SourceCache.class);

    private final SourceRepository repository;
    private final ApplicationEventPublisher events;
    private volatile Map<String, CachedSource> byName = Map.of();

    public SourceCache(SourceRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reload();
    }

    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.is(ConfigKinds.SOURCES)) {
            reload();
        }
    }

    /** Re-reads all sources from the database. */
    public synchronized void reload() {
        byName = repository.findAllByOrderByIdAsc().stream()
                .map(CachedSource::from)
                .collect(Collectors.toUnmodifiableMap(CachedSource::name, Function.identity()));
        log.info("Loaded {} sources", byName.size());
        events.publishEvent(new SourcesReloaded(List.copyOf(byName.values())));
    }

    /** An active source by name. */
    public Optional<CachedSource> findActive(String name) {
        return Optional.ofNullable(byName.get(name)).filter(CachedSource::active);
    }

    /** Immutable copy of a {@link Source}. */
    public record CachedSource(long id, String name, SourceType type, JsonNode config, boolean active) {

        static CachedSource from(Source source) {
            return new CachedSource(source.getId(), source.getName(), source.getType(), source.getConfig().deepCopy(), source.isActive());
        }

        /** {@code config.topic} of a kafka source, or empty. */
        public Optional<String> topic() {
            JsonNode topic = config.get("topic");
            return topic != null && topic.isString() && !topic.asString().isBlank()
                    ? Optional.of(topic.asString()) : Optional.empty();
        }
    }

    /** Published after every reload with the full list of sources. */
    public record SourcesReloaded(List<CachedSource> sources) {
    }
}
