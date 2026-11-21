package com.hackathon.alerting.rules.dedup;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Drops redelivered Kafka messages: {@code SET seen:{event_id} 1 NX EX 3600} (AGENTS.md §6 step 2).
 * Kafka delivers at least once; this makes processing effectively once within an hour.
 */
@Component
public class SeenEvents {

    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public SeenEvents(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return {@code true} the first time an event id is seen, {@code false} for a redelivery */
    public boolean markSeen(UUID eventId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(eventId), "1", TTL));
    }

    /** Undoes {@link #markSeen} when processing failed, so the retried delivery is not skipped. */
    public void forget(UUID eventId) {
        redis.delete(key(eventId));
    }

    private static String key(UUID eventId) {
        return "seen:" + eventId;
    }
}
