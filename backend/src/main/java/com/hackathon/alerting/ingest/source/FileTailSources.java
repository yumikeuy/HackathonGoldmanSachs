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
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tails the files of active {@code file} sources ({@code sources.config.path}) and ingests every new line
 * (AGENTS.md §2.1 bonus, contract in {@code openapi.yaml} {@code SourceInput.config}).
 *
 * <p>With several instances only the one holding the source's lease tails the file and it saves its position, so a
 * failover continues where the previous holder stopped ({@link FileSourceLeases}).
 *
 * <p>One {@link FileTailer} on its own virtual thread per source, polling every {@code alerting.file-source.poll-interval}
 * (500 ms). Tailers follow the source list like {@link ExternalKafkaSources}: added sources start, removed or
 * deactivated ones stop, a changed path or pattern restarts. Lines become events by {@link LogLineFormat}: JSON as is,
 * lines matching {@code config.pattern} as their named groups, anything else as {@code {"message": "<line>"}}. If Kafka is down the same line is retried on the next
 * poll — lines are never skipped.
 */
@Component
public class FileTailSources implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FileTailSources.class);

    private final EventIngestor ingestor;
    private final PayloadParser parser;
    private final FileSourceLeases leases;
    private final Duration pollInterval;
    private final Map<String, Running> running = new HashMap<>();
    private volatile boolean started;

    public FileTailSources(EventIngestor ingestor, PayloadParser parser, FileSourceLeases leases,
                           @Value("${alerting.file-source.poll-interval:500ms}") Duration pollInterval) {
        this.ingestor = ingestor;
        this.parser = parser;
        this.leases = leases;
        this.pollInterval = pollInterval;
    }

    @EventListener
    public synchronized void onSourcesReloaded(SourcesReloaded event) {
        if (!started) {
            return;
        }
        Map<String, FileSpec> wanted = new HashMap<>();
        for (CachedSource source : event.sources()) {
            if (source.active() && source.type() == SourceType.FILE) {
                path(source).ifPresentOrElse(
                        path -> wanted.put(source.name(), new FileSpec(path, LogLineFormat.patternText(source.config()).orElse(null))),
                        () -> log.warn("File source '{}' has no config.path, not tailing", source.name()));
            }
        }
        running.entrySet().removeIf(entry -> {
            if (!Objects.equals(wanted.get(entry.getKey()), entry.getValue().spec())) {
                entry.getValue().stop();
                log.info("Stopped tailing file source '{}' ({})", entry.getKey(), entry.getValue().spec().path());
                return true;
            }
            return false;
        });
        wanted.forEach((name, spec) -> running.computeIfAbsent(name, n -> start(n, spec)));
    }

    /** Whether this instance is tailing the source right now (holds its lease; tests wait for it before appending). */
    public synchronized boolean isTailing(String sourceName) {
        Running run = running.get(sourceName);
        return run != null && run.tailer != null;
    }

    static Optional<String> path(CachedSource source) {
        JsonNode path = source.config().get("path");
        return path != null && path.isString() && !path.asString().isBlank() ? Optional.of(path.asString()) : Optional.empty();
    }

    private Running start(String sourceName, FileSpec spec) {
        Running run = new Running(spec);
        run.thread = Thread.ofVirtual().name("file-source-" + sourceName).start(() -> loop(sourceName, run));
        return run;
    }

    /**
     * Per source: hold the lease (only one instance tails a file), poll, save the checkpoint. A Redis outage keeps the
     * current state — a tailing instance goes on, a waiting one keeps waiting.
     */
    private void loop(String sourceName, Running run) {
        while (!run.stopped) {
            try {
                boolean holder = leases.hold(sourceName);
                if (holder && run.tailer == null) {
                    run.tailer = newTailer(sourceName, run.spec, leases.checkpoint(sourceName).orElse(null));
                } else if (!holder && run.tailer != null) {
                    log.info("File source '{}' is now tailed by another instance", sourceName);
                    run.closeTailer();
                }
            } catch (RuntimeException e) {
                log.warn("File source '{}': lease check failed ({}), keeping the current state", sourceName, e.getMessage());
            }
            if (run.tailer != null) {
                try {
                    run.tailer.poll();
                } catch (Exception e) {
                    // Kafka down or an I/O hiccup: the line stays pending and is offered again next time.
                    log.warn("File source '{}': {} — retrying", sourceName, e.getMessage());
                }
                saveCheckpoint(sourceName, run);
            }
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                break;
            }
        }
        run.closeTailer();
        try {
            leases.release(sourceName);
        } catch (RuntimeException e) {
            // expires by itself
        }
    }

    private FileTailer newTailer(String sourceName, FileSpec spec, FileTailer.Checkpoint resume) {
        LogLineFormat format = new LogLineFormat(spec.pattern() == null ? null : Pattern.compile(spec.pattern()));
        FileTailer tailer = new FileTailer(Path.of(spec.path()),
                line -> ingestor.ingest(SourceType.FILE, sourceName, format.toPayload(line, parser)), true, resume);
        log.info("Tailing file source '{}' at {}{}{}", sourceName, spec.path(),
                spec.pattern() == null ? "" : " with a line pattern",
                resume == null ? "" : " (resuming from the saved position when it is the same file)");
        return tailer;
    }

    private void saveCheckpoint(String sourceName, Running run) {
        run.tailer.checkpoint().filter(checkpoint -> !checkpoint.equals(run.saved)).ifPresent(checkpoint -> {
            try {
                leases.saveCheckpoint(sourceName, checkpoint);
                run.saved = checkpoint;
            } catch (RuntimeException e) {
                // saved on a later poll
            }
        });
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

    /** What a tailer follows; a change of either restarts it. */
    private record FileSpec(String path, String pattern) {
    }

    private static final class Running {
        private final FileSpec spec;
        /** Non-null while this instance holds the lease. */
        private volatile FileTailer tailer;
        private FileTailer.Checkpoint saved;
        private volatile boolean stopped;
        private Thread thread;

        Running(FileSpec spec) {
            this.spec = spec;
        }

        void closeTailer() {
            if (tailer != null) {
                tailer.close();
                tailer = null;
            }
        }

        FileSpec spec() {
            return spec;
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
