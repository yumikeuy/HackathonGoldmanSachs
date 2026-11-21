package com.hackathon.alerting.delivery.simulator;

import com.hackathon.alerting.delivery.model.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * On/off switches of the simulated providers ({@code POST /api/simulator/channels/{c}/toggle}). While a channel is
 * down every attempt on it fails and is retried by the outbox, so nothing is lost when it comes back.
 *
 * <p>The state lives in Redis hash {@value #KEY} (field = channel, {@code "0"} = down; missing = up), so a switch
 * flipped through one instance applies to every instance's outbox worker. The worker asks per message, so reads are
 * cached for {@link #CACHE_TTL}. If Redis is down the last known state is used; flipping a switch then fails (500).
 */
@Component
public class ChannelSwitchboard {

    static final String KEY = "sim:channels";
    static final Duration CACHE_TTL = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(ChannelSwitchboard.class);

    /** Flips a field between "1" (or missing) and "0"; returns the new value. */
    private static final RedisScript<String> TOGGLE = new DefaultRedisScript<>("""
            local next = redis.call('HGET', KEYS[1], ARGV[1]) == '0' and '1' or '0'
            redis.call('HSET', KEYS[1], ARGV[1], next)
            return next
            """, String.class);

    private final StringRedisTemplate redis;
    private volatile Map<Channel, Boolean> cached = allUp();
    /** {@link System#nanoTime()} of the last Redis read (successful or not); 0 = read on next use. */
    private volatile long readAt;

    public ChannelSwitchboard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isEnabled(Channel channel) {
        return snapshot().get(channel);
    }

    /** Flips the channel for all instances and returns its new state. */
    public ChannelState toggle(Channel channel) {
        String next = redis.execute(TOGGLE, List.of(KEY), channel.name());
        readAt = 0;
        return new ChannelState(channel, !"0".equals(next));
    }

    public List<ChannelState> states() {
        readAt = 0;
        Map<Channel, Boolean> states = snapshot();
        return Arrays.stream(Channel.values()).map(c -> new ChannelState(c, states.get(c))).toList();
    }

    private Map<Channel, Boolean> snapshot() {
        long now = System.nanoTime();
        if (readAt != 0 && now - readAt < CACHE_TTL.toNanos()) {
            return cached;
        }
        readAt = now;   // also after a failure: do not hit a dead Redis for every message
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(KEY);
            Map<Channel, Boolean> states = allUp();
            raw.forEach((field, value) -> {
                try {
                    states.put(Channel.valueOf(field.toString()), !"0".equals(value));
                } catch (IllegalArgumentException ignored) {
                    // unknown field — not a channel
                }
            });
            cached = states;
        } catch (RuntimeException e) {
            log.debug("Redis unavailable, using the last known channel switches: {}", e.getMessage());
        }
        return cached;
    }

    private static Map<Channel, Boolean> allUp() {
        Map<Channel, Boolean> states = new EnumMap<>(Channel.class);
        for (Channel channel : Channel.values()) {
            states.put(channel, true);
        }
        return states;
    }

    /** {@code ChannelState} of openapi.yaml. */
    public record ChannelState(Channel channel, boolean enabled) {
    }
}
