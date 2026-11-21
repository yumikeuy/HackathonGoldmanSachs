package com.hackathon.alerting.rules.unrouted;

import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.Event;
import com.hackathon.alerting.ingest.repository.EventRepository;
import com.hackathon.alerting.rules.alert.AlertSummary;
import com.hackathon.alerting.rules.dedup.DedupStore;
import com.hackathon.alerting.rules.dedup.FingerprintCalculator;
import com.hackathon.alerting.rules.metrics.ProcessingMetrics;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.push.AlertPublisher;
import com.hackathon.alerting.rules.repository.AlertRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Handles an event no team matched (AGENTS.md §6 step 12): one UNROUTED alert (severity UNKNOWN, no team)
 * per source and top-level shape, a row in {@code events} for every such event, and a push to {@code /topic/unrouted}.
 *
 * <p>Repeats are deduplicated exactly like rule alerts, with the default window. No runbook and no
 * notifications: there is no team to notify — the unrouted page in the UI is where these are triaged.
 */
@Component
public class UnroutedEventHandler {

    /** Dedup window for unrouted groups (there is no rule to take it from). */
    static final int WINDOW_SEC = Rule.DEFAULT_DEDUP_WINDOW_SEC;

    private final FingerprintCalculator fingerprints;
    private final DedupStore dedup;
    private final AlertRepository alerts;
    private final EventRepository events;
    private final AlertPublisher publisher;
    private final TransactionTemplate tx;
    private final ProcessingMetrics metrics;

    public UnroutedEventHandler(FingerprintCalculator fingerprints, DedupStore dedup, AlertRepository alerts,
                                EventRepository events, AlertPublisher publisher, TransactionTemplate tx,
                                ProcessingMetrics metrics) {
        this.fingerprints = fingerprints;
        this.dedup = dedup;
        this.alerts = alerts;
        this.events = events;
        this.publisher = publisher;
        this.tx = tx;
        this.metrics = metrics;
    }

    public void handle(EventEnvelope envelope) {
        String fingerprint = fingerprints.forUnrouted(envelope.sourceName(), envelope.rawPayload());
        if (!dedup.tryClaim(fingerprint, WINDOW_SEC)) {
            dedup.countRepeat(fingerprint, WINDOW_SEC);
            metrics.repeat();
            // While the first event is still creating the alert the binding is pending: the row is kept unlinked.
            OptionalLong bound = dedup.binding(fingerprint).alertId();
            Long alertId = bound.isPresent() ? bound.getAsLong() : null;
            tx.executeWithoutResult(status -> saveEvent(envelope, alertId));
            return;
        }
        Created created;
        try {
            created = tx.execute(status -> create(envelope, fingerprint));
        } catch (RuntimeException e) {
            dedup.releaseClaim(fingerprint);
            throw e;
        }
        dedup.bindAlert(fingerprint, created.alertId());
        if (created.summary().isPresent()) {
            metrics.alertCreated(created.summary().get().severity());
            publisher.publish(created.summary().get());
        } else {
            dedup.countRepeat(fingerprint, WINDOW_SEC);
            metrics.repeat();
        }
    }

    /** Inserts the alert unless one is already open (Redis lost the key), and records the event. */
    private Created create(EventEnvelope envelope, String fingerprint) {
        Alert draft = Alert.unrouted(fingerprint, "Unrouted event from " + envelope.sourceName(),
                envelope.rawPayload(), envelope.ingestedAt());
        Optional<Long> inserted = alerts.insertIfNoOpenAlert(draft);
        long alertId = inserted.orElseGet(() -> alerts.findOpenByFingerprint(fingerprint).orElseThrow().getId());
        saveEvent(envelope, alertId);
        Optional<AlertSummary> summary = inserted.map(id -> AlertSummary.from(alerts.findById(id).orElseThrow()));
        return new Created(alertId, summary);
    }

    private void saveEvent(EventEnvelope envelope, Long alertId) {
        Alert alert = alertId == null ? null : alerts.getReferenceById(alertId);
        events.save(Event.unrouted(envelope.eventId(), envelope.sourceName(), envelope.ingestedAt(), envelope.rawPayload(), alert));
    }

    /** @param summary present only if this call created the alert */
    private record Created(long alertId, Optional<AlertSummary> summary) {
    }
}
