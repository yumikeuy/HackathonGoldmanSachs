package com.hackathon.alerting.ingest.source;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates file sources between instances (docs/operations.md, "Several instances"):
 * <ul>
 *   <li><b>lease</b> {@code lock:file-source:{name}} — only the instance holding it tails the file, so a line is not
 *       ingested twice; it is renewed on every poll and expires {@link #LEASE_TTL} after its holder dies, then another
 *       instance takes over;</li>
 *   <li><b>checkpoint</b> {@code file-pos:{name}} (file identity + byte offset) — the new holder continues where the
 *       previous one stopped: no line lost, at most the last poll's lines repeated after a crash (at-least-once).</li>
 * </ul>
 * Redis errors are thrown to the caller, which keeps its current state.
 */
@Component
public class FileSourceLeases {

    static final Duration LEASE_TTL = Duration.ofSeconds(10);
    static final Duration CHECKPOINT_TTL = Duration.ofDays(30);
    static final String LEASE_PREFIX = "lock:file-source:";
    static final String CHECKPOINT_PREFIX = "file-pos:";

    /** Takes a free lease or renews our own; 1 if we hold it afterwards. */
    private static final RedisScript<Long> HOLD = new DefaultRedisScript<>("""
            local holder = redis.call('GET', KEYS[1])
            if not holder then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if holder == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString();

    public FileSourceLeases(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Acquires or renews the lease of a source; {@code true} if this instance may tail it. */
    public boolean hold(String sourceName) {
        Long held = redis.execute(HOLD, List.of(LEASE_PREFIX + sourceName), instanceId, String.valueOf(LEASE_TTL.toMillis()));
        return held != null && held == 1;
    }

    /** Gives the lease up (only if we hold it), so another instance can take over at once. */
    public void release(String sourceName) {
        redis.execute(RELEASE, List.of(LEASE_PREFIX + sourceName), instanceId);
    }

    public Optional<FileTailer.Checkpoint> checkpoint(String sourceName) {
        Map<Object, Object> fields = redis.opsForHash().entries(CHECKPOINT_PREFIX + sourceName);
        Object key = fields.get("fileKey");
        Object position = fields.get("position");
        if (key == null || position == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FileTailer.Checkpoint(key.toString(), Long.parseLong(position.toString())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void saveCheckpoint(String sourceName, FileTailer.Checkpoint checkpoint) {
        String key = CHECKPOINT_PREFIX + sourceName;
        redis.opsForHash().putAll(key, Map.of("fileKey", checkpoint.fileKey(), "position", String.valueOf(checkpoint.position())));
        redis.expire(key, CHECKPOINT_TTL);
    }
}
