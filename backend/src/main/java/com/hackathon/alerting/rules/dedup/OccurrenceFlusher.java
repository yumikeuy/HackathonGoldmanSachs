package com.hackathon.alerting.rules.dedup;

import com.hackathon.alerting.rules.alert.AlertSummary;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.push.AlertPublisher;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.rules.repository.OccurrenceDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Moves repeat counters from Redis to Postgres once a second (AGENTS.md §6 "background tasks"):
 * {@code SPOP dirty:fp 500} → resolve each fingerprint to its alert → {@code GETDEL cnt:{fp}} →
 * one JDBC batch {@code occurrences_count += n, last_seen_at = now} → push the updated alerts (throttled).
 *
 * <p>{@code last_seen_at} is the flush time — within a second of the real last repeat.
 * Fingerprints whose alert is still being created ({@code pending}) are put back for the next run.
 * Safe to run on several instances: {@code SPOP} and {@code GETDEL} hand every count to exactly one of them.
 */
@Component
public class OccurrenceFlusher {

    private static final Logger log = LoggerFactory.getLogger(OccurrenceFlusher.class);
    static final int BATCH = 500;

    private final DedupStore dedup;
    private final AlertRepository alerts;
    private final AlertPublisher publisher;
    private final Clock clock;

    public OccurrenceFlusher(DedupStore dedup, AlertRepository alerts, AlertPublisher publisher, Clock clock) {
        this.dedup = dedup;
        this.alerts = alerts;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void scheduledFlush() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Occurrence flush failed, will retry", e);
        }
    }

    /**
     * One flush round.
     *
     * @return number of alerts updated
     */
    public int flush() {
        List<String> fingerprints = dedup.popDirty(BATCH);
        if (fingerprints.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        Map<Long, Long> countsByAlert = new HashMap<>();
        for (String fingerprint : fingerprints) {
            OptionalLong alertId = resolveAlert(fingerprint);
            if (alertId.isEmpty()) {
                continue;
            }
            long count = dedup.takeCount(fingerprint);
            if (count > 0) {
                countsByAlert.merge(alertId.getAsLong(), count, Long::sum);
            }
        }
        if (countsByAlert.isEmpty()) {
            return 0;
        }
        List<OccurrenceDelta> deltas = countsByAlert.entrySet().stream()
                .map(e -> new OccurrenceDelta(e.getKey(), Math.toIntExact(e.getValue()), now))
                .toList();
        int updated = alerts.addOccurrences(deltas);
        for (Alert alert : alerts.findWithRuleByIdIn(countsByAlert.keySet())) {
            publisher.publishThrottled(AlertSummary.from(alert));
        }
        return updated;
    }

    /**
     * Alert of a dirty fingerprint: the id bound in Redis; if the key expired (the window ended while the alert
     * is still open), the open alert from Postgres. Empty if there is nothing to count into (or not yet).
     */
    private OptionalLong resolveAlert(String fingerprint) {
        DedupStore.Binding binding = dedup.binding(fingerprint);
        if (binding.pending()) {
            dedup.markDirty(fingerprint);
            return OptionalLong.empty();
        }
        if (binding.alertId().isPresent()) {
            return binding.alertId();
        }
        return alerts.findOpenByFingerprint(fingerprint)
                .map(alert -> OptionalLong.of(alert.getId()))
                .orElseGet(() -> {
                    long dropped = dedup.takeCount(fingerprint);
                    if (dropped > 0) {
                        log.debug("Dropped {} repeats of fingerprint {}: no open alert", dropped, fingerprint);
                    }
                    return OptionalLong.empty();
                });
    }
}
