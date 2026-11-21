package com.hackathon.alerting.ingest.fields;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Catalog of field paths seen in events, for autocompletion in the condition builder (AGENTS.md §3):
 * hash {@value #PATHS_KEY} path → JSON type, and a HyperLogLog {@code card:{path}} (24 h) estimating how many
 * distinct values the field has.
 *
 * <p>Paths are dotted object keys ({@code trade.notional}); arrays are recorded as a field of type {@code array}
 * and not descended into. Updated for every ingested event in one pipelined round trip; failures are logged
 * and never block ingestion.
 */
@Component
public class FieldCatalog {

    public static final String PATHS_KEY = "fields:paths";
    public static final String CARDINALITY_PREFIX = "card:";

    static final Duration CARDINALITY_TTL = Duration.ofHours(24);
    /** Guards against huge or deeply nested events. */
    static final int MAX_PATHS_PER_EVENT = 200;
    static final int MAX_DEPTH = 8;

    private static final Logger log = LoggerFactory.getLogger(FieldCatalog.class);

    private final StringRedisTemplate redis;

    public FieldCatalog(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Records the fields of one event. */
    public void record(JsonNode event) {
        Map<String, JsonNode> fields = flatten(event);
        if (fields.isEmpty()) {
            return;
        }
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                byte[] pathsKey = bytes(PATHS_KEY);
                long ttl = CARDINALITY_TTL.toSeconds();
                fields.forEach((path, value) -> {
                    connection.hashCommands().hSet(pathsKey, bytes(path), bytes(typeOf(value)));
                    if (value.isValueNode()) {
                        byte[] cardKey = bytes(CARDINALITY_PREFIX + path);
                        connection.hyperLogLogCommands().pfAdd(cardKey, bytes(value.asString()));
                        connection.keyCommands().expire(cardKey, ttl);
                    }
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Could not update the field catalog in Redis: {}", e.getMessage());
        }
    }

    /** All known paths with their type and approximate number of distinct values, sorted by path. */
    public List<KnownField> fields() {
        Map<Object, Object> raw = redis.opsForHash().entries(PATHS_KEY);
        Map<String, String> types = new TreeMap<>();
        raw.forEach((path, type) -> types.put(path.toString(), type.toString()));
        if (types.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(types.keySet());
        List<Object> counts = redis.executePipelined((RedisCallback<Object>) connection -> {
            paths.forEach(path -> connection.hyperLogLogCommands().pfCount(bytes(CARDINALITY_PREFIX + path)));
            return null;
        });
        List<KnownField> result = new ArrayList<>(paths.size());
        for (int i = 0; i < paths.size(); i++) {
            long cardinality = counts.get(i) instanceof Number number ? number.longValue() : 0;
            result.add(new KnownField(paths.get(i), types.get(paths.get(i)), cardinality));
        }
        return result;
    }

    /** Leaf and container fields of an event by dotted path, at most {@link #MAX_PATHS_PER_EVENT}. */
    static Map<String, JsonNode> flatten(JsonNode event) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (event != null && event.isObject()) {
            collect(event, "", 1, fields);
        }
        return fields;
    }

    private static void collect(JsonNode object, String prefix, int depth, Map<String, JsonNode> fields) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            if (fields.size() >= MAX_PATHS_PER_EVENT) {
                return;
            }
            String path = prefix + entry.getKey();
            JsonNode value = entry.getValue();
            fields.put(path, value);
            if (value.isObject() && depth < MAX_DEPTH) {
                collect(value, path + ".", depth + 1, fields);
            }
        }
    }

    /** JSON type name as in {@code FieldInfo.type} of openapi.yaml. */
    static String typeOf(JsonNode value) {
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isNull()) {
            return "null";
        }
        return "string";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** A path from the catalog. */
    public record KnownField(String path, String type, long cardinality) {
    }
}
