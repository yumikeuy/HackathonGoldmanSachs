package com.hackathon.alerting.ingest.source;

import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.source.SourceCache.CachedSource;
import com.hackathon.alerting.ingest.source.SourceCache.SourcesReloaded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tails the files of active {@code file} sources ({@code sources.config.path}) and ingests every new line
 * (AGENTS.md §2.1 bonus, contract in {@code openapi.yaml} {@code SourceInput.config}).
 *
 * <p>One {@link FileTailer} on its own virtual thread per source, polling every {@code alerting.file-source.poll-interval}
 * (500 ms). Tailers follow the source list like {@link ExternalKafkaSources}: added sources start, removed or
 * deactivated ones stop, a changed path restarts. A line that looks like JSON ({@code {…}} / {@code […]}) and parses is
 * ingested as is, anything else as {@code {"message": "<line>"}}. If Kafka is down the same line is retried on the next
 * poll — lines are never skipped.
 */
@Component
public class FileTailSources implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FileTailSources.class);

    private final EventIngestor ingestor;
    private final JsonMapper json;
    private final Duration pollInterval;
    private final Map<String, Running> running = new HashMap<>();
    private volatile boolean started;

    public FileTailSources(EventIngestor ingestor, JsonMapper json,
                           @Value("${alerting.file-source.poll-interval:500ms}") Duration pollInterval) {
        this.ingestor = ingestor;
        this.json = json;
        this.pollInterval = pollInterval;
    }

    @EventListener
    public synchronized void onSourcesReloaded(SourcesReloaded event) {
        if (!started) {
            return;
        }
        Map<String, String> wanted = new HashMap<>();
        for (CachedSource source : event.sources()) {
            if (source.active() && source.type() == SourceType.FILE) {
                path(source).ifPresentOrElse(
                        path -> wanted.put(source.name(), path),
                        () -> log.warn("File source '{}' has no config.path, not tailing", source.name()));
            }
        }
        running.entrySet().removeIf(entry -> {
            if (!Objects.equals(wanted.get(entry.getKey()), entry.getValue().path())) {
                entry.getValue().stop();
                log.info("Stopped tailing file source '{}' ({})", entry.getKey(), entry.getValue().path());
                return true;
            }
            return false;
        });
        wanted.forEach((name, path) -> running.computeIfAbsent(name, n -> start(n, path)));
    }

    /** Whether a tailer runs for the source (tests wait for it before appending lines). */
    public synchronized boolean isTailing(String sourceName) {
        return running.containsKey(sourceName);
    }

    static Optional<String> path(CachedSource source) {
        JsonNode path = source.config().get("path");
        return path != null && path.isString() && !path.asString().isBlank() ? Optional.of(path.asString()) : Optional.empty();
    }

    private Running start(String sourceName, String path) {
        // Created on the caller's thread so "tailing" really starts at the current end of the file.
        FileTailer tailer = new FileTailer(Path.of(path), line -> ingestor.ingest(SourceType.FILE, sourceName, toPayload(line)), true);
        Running run = new Running(path, tailer);
        run.thread = Thread.ofVirtual().name("file-source-" + sourceName).start(() -> loop(sourceName, run));
        log.info("Tailing file source '{}' at {}", sourceName, path);
        return run;
    }

    private void loop(String sourceName, Running run) {
        while (!run.stopped) {
            try {
                run.tailer.poll();
            } catch (Exception e) {
                // Kafka down or an I/O hiccup: the line stays pending and is offered again next time.
                log.warn("File source '{}': {} — retrying", sourceName, e.getMessage());
            }
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                break;
            }
        }
        run.tailer.close();
    }

    /** A JSON object/array line as is; any other line as {@code {"message": line}}. */
    JsonNode toPayload(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return json.readTree(trimmed);
            } catch (JacksonException e) {
                // not JSON after all
            }
        }
        return JsonNodeFactory.instance.objectNode().put("message", line);
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void stop() {
        started = false;
        running.values().forEach(Running::stop);
        running.clear();
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    private static final class Running {
        private final String path;
        private final FileTailer tailer;
        private volatile boolean stopped;
        private Thread thread;

        Running(String path, FileTailer tailer) {
            this.path = path;
            this.tailer = tailer;
        }

        String path() {
            return path;
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
