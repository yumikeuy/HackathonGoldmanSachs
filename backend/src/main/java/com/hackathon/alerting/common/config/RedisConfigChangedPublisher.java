package com.hackathon.alerting.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link ConfigChangedPublisher} over Redis Pub/Sub (channel {@value #CHANNEL}, AGENTS.md §3).
 *
 * <p>{@link #publish} sends the kind to Redis after the current transaction commits; the
 * {@link ConfigChangedSubscriber} receives it on every instance (including this one)
 * and re-publishes it as a local {@link ConfigChangedEvent} for the caches. If Redis is unreachable the event
 * is delivered locally right away, so at least this instance never serves stale configuration.
 */
@Component
public class RedisConfigChangedPublisher implements ConfigChangedPublisher {

    public static final String CHANNEL = "config.changed";

    private static final Logger log = LoggerFactory.getLogger(RedisConfigChangedPublisher.class);

    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    public RedisConfigChangedPublisher(StringRedisTemplate redis, ApplicationEventPublisher events) {
        this.redis = redis;
        this.events = events;
    }

    @Override
    public void publish(String what) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(what);
                }
            });
        } else {
            send(what);
        }
    }

    private void send(String what) {
        try {
            redis.convertAndSend(CHANNEL, what);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, delivering config change '{}' to this instance only: {}", what, e.getMessage());
            events.publishEvent(new ConfigChangedEvent(what));
        }
    }
}
