package com.hackathon.alerting.ingest.recent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RecentEvents} in Redis list {@value #KEY}: {@code LPUSH} + {@code LTRIM 0 999}, read with {@code LRANGE}.
 * Shared by all instances. Redis is not the source of truth: if it is down the event is still ingested,
 * only the preview misses it.
 */
@Component
public class RedisRecentEvents implements RecentEvents {

    public static final String KEY = "recent:events";

    private static final Logger log = LoggerFactory.getLogger(RedisRecentEvents.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisRecentEvents(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void add(JsonNode rawPayload) {
        String value = json.writeValueAsString(rawPayload);
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
                connection.listCommands().lPush(key, value.getBytes(StandardCharsets.UTF_8));
                connection.listCommands().lTrim(key, 0, CAPACITY - 1);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Could not store a recent event in Redis: {}", e.getMessage());
        }
    }

    @Override
    public List<JsonNode> latest(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> values = redis.opsForList().range(KEY, 0, Math.min(limit, CAPACITY) - 1L);
        List<JsonNode> result = new ArrayList<>(values == null ? 0 : values.size());
        if (values != null) {
            values.forEach(value -> result.add(json.readTree(value)));
        }
        return result;
    }
}
