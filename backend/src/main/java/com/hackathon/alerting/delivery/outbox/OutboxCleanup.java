package com.hackathon.alerting.delivery.outbox;

import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/** Hourly: deletes SENT outbox rows older than one day (AGENTS.md §6). PENDING and FAILED rows are kept. */
@Component
public class OutboxCleanup {

    static final Duration KEEP_SENT = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxTaskRepository outbox;
    private final Clock clock;

    public OutboxCleanup(OutboxTaskRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Scheduled(cron = "${alerting.outbox.cleanup-cron:0 0 * * * *}")
    @Transactional
    public void deleteOldSent() {
        int deleted = outbox.deleteSentBefore(clock.instant().minus(KEEP_SENT));
        if (deleted > 0) {
            log.info("Deleted {} sent outbox rows older than {}", deleted, KEEP_SENT);
        }
    }
}
