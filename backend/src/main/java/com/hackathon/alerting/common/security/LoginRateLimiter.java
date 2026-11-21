package com.hackathon.alerting.common.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Brute-force protection for {@code POST /api/auth/login}: at most {@value #MAX_ATTEMPTS} attempts per email per
 * minute (Redis {@code rl:login:{email}}), successful or not. Allowed when Redis is down.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Counts an attempt; {@code false} if the email is over the limit. */
    public boolean tryAcquire(String email) {
        String key = "rl:login:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, WINDOW);
            }
            return count == null || count <= MAX_ATTEMPTS;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
