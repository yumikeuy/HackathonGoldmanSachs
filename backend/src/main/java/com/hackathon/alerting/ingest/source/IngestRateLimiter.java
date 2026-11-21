package com.hackathon.alerting.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Per-source limit on {@code POST /api/ingest/{source}}: at most {@code alerting.ingest.rate-limit-per-second} events per
 * second (fixed one-second window, Redis counter {@code rl:ingest:{source}}, TTL 1 s). Protects Kafka and the processor
 * from one noisy or hostile sender; other sources are unaffected.
 *
 * <p>A limit of 0 or less disables the check. If Redis is down everything is allowed — refusing events because the
 * limiter is unavailable would be worse than not limiting.
 */
@Component
public class IngestRateLimiter {

    static final String KEY_PREFIX = "rl:ingest:";

    private static final Logger log = LoggerFactory.getLogger(IngestRateLimiter.class);

    private static final RedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], 1000) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int perSecond;

    public IngestRateLimiter(StringRedisTemplate redis, @Value("${alerting.ingest.rate-limit-per-second:1000}") int perSecond) {
        this.redis = redis;
        this.perSecond = perSecond;
    }

    /** Counts one event of the source; {@code false} if the source is over its limit for the current second. */
    public boolean tryAcquire(String sourceName) {
        if (perSecond <= 0) {
            return true;
        }
        try {
            Long count = redis.execute(INCREMENT, List.of(KEY_PREFIX + sourceName));
            return count == null || count <= perSecond;
        } catch (RuntimeException e) {
            log.debug("Ingest rate limiter unavailable, allowing: {}", e.getMessage());
            return true;
        }
    }

    public int perSecond() {
        return perSecond;
    }
}
