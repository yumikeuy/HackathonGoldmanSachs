package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.rules.repository.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Hourly cleanup (AGENTS.md §6): deletes alerts resolved more than {@link #RETENTION} ago; their outbox rows
 * and audit records go with them by ON DELETE CASCADE.
 */
@Component
public class AlertCleanup {

    private static final Logger log = LoggerFactory.getLogger(AlertCleanup.class);
    static final Duration RETENTION = Duration.ofDays(7);

    private final AlertRepository alerts;
    private final Clock clock;

    public AlertCleanup(AlertRepository alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void deleteOldResolvedAlerts() {
        int deleted = alerts.deleteResolvedBefore(clock.instant().minus(RETENTION));
        if (deleted > 0) {
            log.info("Deleted {} alerts resolved more than {} ago", deleted, RETENTION);
        }
    }
}
