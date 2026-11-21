package com.hackathon.alerting.delivery.ratelimit;

import com.hackathon.alerting.delivery.model.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * At most {@value #LIMIT} notifications per user and channel per minute: Redis counter {@code rl:{user}:{channel}},
 * TTL 60 s from the first message of the window (AGENTS.md §3).
 *
 * <p>{@link #acquire} atomically takes up to {@code wanted} of the permits left in the window (Lua script).
 * If Redis is down everything is allowed — losing the limit is better than losing notifications.
 */
@Component
public class RateLimiter {

    public static final int LIMIT = 20;
    public static final Duration WINDOW = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final RedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local granted = math.min(tonumber(ARGV[2]), math.max(0, tonumber(ARGV[1]) - current))
            if granted > 0 then
              if redis.call('INCRBY', KEYS[1], granted) == granted then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
              end
            end
            return {granted, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(long userId, Channel channel) {
        return "rl:" + userId + ":" + channel;
    }

    /**
     * Takes up to {@code wanted} permits.
     *
     * @return how many were granted (0..wanted) and when the window resets
     */
    public Permits acquire(long userId, Channel channel, int wanted) {
        try {
            List<?> result = redis.execute(ACQUIRE, List.of(key(userId, channel)),
                    String.valueOf(LIMIT), String.valueOf(wanted), String.valueOf(WINDOW.toSeconds()));
            int granted = ((Number) result.get(0)).intValue();
            long ttl = ((Number) result.get(1)).longValue();
            return new Permits(granted, ttl > 0 ? Duration.ofSeconds(ttl) : WINDOW);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing {} {} message(s) to user {}: {}", wanted, channel, userId, e.getMessage());
            return new Permits(wanted, WINDOW);
        }
    }

    /**
     * @param granted     permits taken
     * @param windowReset time until the counter expires and new permits are available
     */
    public record Permits(int granted, Duration windowReset) {
    }
}
