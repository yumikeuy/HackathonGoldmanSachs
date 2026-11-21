package com.hackathon.alerting.common.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STEP-0 STUB of {@link ConfigChangedPublisher} (owner Role 1): delivers {@link ConfigChangedEvent}
 * to listeners of <b>this instance only</b>, after the current transaction commits.
 *
 * <p>Role 1 replaces it with Redis Pub/Sub on channel {@code config.changed}: publish to Redis after
 * commit, and a Redis listener that re-publishes {@link ConfigChangedEvent} locally on every instance.
 * Listeners do not change.
 */
@Component
public class LocalConfigChangedPublisher implements ConfigChangedPublisher {

    private final ApplicationEventPublisher events;

    public LocalConfigChangedPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(String what) {
        ConfigChangedEvent event = new ConfigChangedEvent(what);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publishEvent(event);
                }
            });
        } else {
            events.publishEvent(event);
        }
    }
}
