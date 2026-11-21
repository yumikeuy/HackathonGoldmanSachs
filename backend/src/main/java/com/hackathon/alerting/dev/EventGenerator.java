package com.hackathon.alerting.dev;

import com.hackathon.alerting.common.web.ConflictException;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.ServiceUnavailableException;
import com.hackathon.alerting.ingest.source.EventIngestor;
import com.hackathon.alerting.ingest.source.SourceCache;
import com.hackathon.alerting.ingest.source.SourceCache.CachedSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Sends test events through the <b>real</b> ingest path ({@link EventIngestor}: Kafka with {@code acks=all}, recent
 * events, field catalog) at a given rate, in the background.
 *
 * <p>At most {@value #MAX_CONCURRENT_RUNS} runs at a time (more → 409). Each run paces itself to
 * {@code ratePerSecond} on its own virtual thread; since every send waits for Kafka's acknowledgement, a single run
 * tops out at roughly 1/(ack latency) events per second — use k6 ({@code loadtest/}) for real load. A run stops early
 * when Kafka is unavailable or the application shuts down.
 */
@Service
@ConditionalOnProperty(name = "alerting.dev.generator-enabled", havingValue = "true")
public class EventGenerator {

    static final int MAX_CONCURRENT_RUNS = 4;

    private static final Logger log = LoggerFactory.getLogger(EventGenerator.class);

    private final SourceCache sources;
    private final EventIngestor ingestor;
    private final SampleEvents samples;
    private final TemplateExpander templates;
    private final Semaphore runs = new Semaphore(MAX_CONCURRENT_RUNS);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EventGenerator(SourceCache sources, EventIngestor ingestor, SampleEvents samples, TemplateExpander templates) {
        this.sources = sources;
        this.ingestor = ingestor;
        this.samples = samples;
        this.templates = templates;
    }

    /**
     * Validates the request and starts a run.
     *
     * @throws InvalidRequestException for an unknown or inactive source or an invalid template
     * @throws ConflictException       when {@value #MAX_CONCURRENT_RUNS} runs are already active
     */
    public GenerateAccepted start(GenerateRequest request) {
        CachedSource source = sources.findActive(request.source())
                .orElseThrow(() -> new InvalidRequestException("Source '" + request.source() + "' does not exist or is not active"));
        JsonNode template = request.templateOrNull();
        if (template != null) {
            templates.validate(template);
        }
        if (!runs.tryAcquire()) {
            throw new ConflictException("At most " + MAX_CONCURRENT_RUNS + " generations can run at once; try again later");
        }
        int count = request.countOrDefault();
        int rate = request.rateOrDefault();
        try {
            executor.execute(() -> {
                try {
                    run(source, template, count, rate);
                } finally {
                    runs.release();
                }
            });
        } catch (RuntimeException e) {
            runs.release();
            throw e;
        }
        log.info("Generating {} events into source '{}' at {}/s", count, source.name(), rate);
        return new GenerateAccepted(count);
    }

    private void run(CachedSource source, JsonNode template, int count, int ratePerSecond) {
        long start = System.nanoTime();
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / ratePerSecond;
        for (int i = 0; i < count; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Generation into '{}' interrupted after {} events", source.name(), i);
                return;
            }
            long wait = start + i * intervalNanos - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            JsonNode payload = template == null ? samples.get(i) : templates.expand(template, i + 1L);
            try {
                ingestor.ingest(source.type(), source.name(), payload);
            } catch (ServiceUnavailableException e) {
                log.warn("Generation into '{}' stopped after {} of {} events: {}", source.name(), i, count, e.getBody().getDetail());
                return;
            }
        }
        log.info("Generated {} events into source '{}'", count, source.name());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
