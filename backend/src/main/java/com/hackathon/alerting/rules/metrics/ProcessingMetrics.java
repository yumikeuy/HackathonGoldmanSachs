package com.hackathon.alerting.rules.metrics;

import com.hackathon.alerting.rules.model.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Processing metrics (roles.md §5 rule 6: {@code alerting.<area>.<what>}, low-cardinality tags only).
 *
 * <table>
 *   <tr><th>Meter</th><th>Type</th><th>Tags</th></tr>
 *   <tr><td>{@code alerting.events.processed}</td><td>counter</td><td>{@code outcome}: routed, unrouted, duplicate</td></tr>
 *   <tr><td>{@code alerting.alerts.created}</td><td>counter</td><td>{@code severity}</td></tr>
 *   <tr><td>{@code alerting.alerts.repeats}</td><td>counter</td><td>—</td></tr>
 *   <tr><td>{@code alerting.alerts.resolved}</td><td>counter</td><td>{@code by}: user, rule</td></tr>
 *   <tr><td>{@code alerting.processing.delay}</td><td>timer</td><td>— (from {@code ingested_at} to processed)</td></tr>
 * </table>
 * Meters are registered once here, so the hot path only increments.
 */
@Component
public class ProcessingMetrics {

    /** Result of processing one {@code events.ingest} record. */
    public enum Outcome {
        /** At least one team matched. */
        ROUTED,
        /** No team matched — an UNROUTED alert. */
        UNROUTED,
        /** A redelivery, skipped by {@code seen:{event_id}}. */
        DUPLICATE
    }

    /** Who resolved an alert. */
    public enum ResolvedBy {
        USER,
        RULE
    }

    private final Map<Outcome, Counter> processed = new EnumMap<>(Outcome.class);
    private final Map<Severity, Counter> created = new EnumMap<>(Severity.class);
    private final Map<ResolvedBy, Counter> resolved = new EnumMap<>(ResolvedBy.class);
    private final Counter repeats;
    private final Timer delay;

    public ProcessingMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            processed.put(outcome, Counter.builder("alerting.events.processed")
                    .description("Events taken from events.ingest, by outcome")
                    .tag("outcome", lower(outcome)).register(registry));
        }
        for (Severity severity : Severity.values()) {
            created.put(severity, Counter.builder("alerting.alerts.created")
                    .description("Alerts opened, by severity")
                    .tag("severity", severity.name()).register(registry));
        }
        for (ResolvedBy by : ResolvedBy.values()) {
            resolved.put(by, Counter.builder("alerting.alerts.resolved")
                    .description("Alerts resolved, by a user or by a rule's resolve_condition")
                    .tag("by", lower(by)).register(registry));
        }
        repeats = Counter.builder("alerting.alerts.repeats")
                .description("Events counted as repeats of an existing alert")
                .register(registry);
        delay = Timer.builder("alerting.processing.delay")
                .description("Time from ingestion (ingested_at) until the event was processed")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void eventProcessed(Outcome outcome) {
        processed.get(outcome).increment();
    }

    public void alertCreated(Severity severity) {
        created.get(severity).increment();
    }

    public void repeat() {
        repeats.increment();
    }

    public void alertResolved(ResolvedBy by) {
        resolved.get(by).increment();
    }

    /** Negative delays (clock skew between instances) are recorded as zero. */
    public void processingDelay(Duration duration) {
        delay.record(duration.isNegative() ? Duration.ZERO : duration);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
