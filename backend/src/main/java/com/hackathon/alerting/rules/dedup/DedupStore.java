package com.hackathon.alerting.rules.dedup;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Redis side of deduplication (AGENTS.md §3, §6 step 7). Keys:
 * <ul>
 *   <li>{@code dedup:{fp}} — {@value #PENDING} while the first event of a group creates the alert, then the alert id;
 *       TTL = the rule's dedup window;</li>
 *   <li>{@code cnt:{fp}} — repeats since the last flush; TTL = window + 60 s;</li>
 *   <li>{@code dirty:fp} — set of fingerprints whose counters must be flushed to Postgres.</li>
 * </ul>
 * Postgres stays the source of truth: if Redis loses these keys, the unique index on open fingerprints still
 * prevents a duplicate alert and only a few repeats go uncounted.
 */
@Component
public class DedupStore {

    public static final String PENDING = "pending";
    static final String DIRTY_SET = "dirty:fp";

    /** Extra lifetime of a counter over the dedup window, so the flusher always sees it. */
    private static final long COUNTER_GRACE_SEC = 60;

    /** {@code INCR cnt:{fp}}, refresh its TTL, mark the fingerprint dirty — one round trip, atomic. */
    private static final RedisScript<Long> COUNT_REPEAT = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            redis.call('SADD', KEYS[2], ARGV[2])
            return n
            """, Long.class);

    /** {@code SET dedup:{fp} {id} XX KEEPTTL}: bind the alert id, keep the window's remaining TTL. */
    private static final RedisScript<String> BIND = RedisScript.of(
            "return redis.call('SET', KEYS[1], ARGV[1], 'XX', 'KEEPTTL')", String.class);

    /** Delete {@code dedup:{fp}} only if it is still {@value #PENDING} (compare-and-delete). */
    private static final RedisScript<Long> RELEASE_PENDING = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public DedupStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * {@code SET dedup:{fp} pending NX EX window}.
     *
     * @return {@code true} if this caller is the first of the window and must create the alert
     */
    public boolean tryClaim(String fingerprint, int windowSec) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(dedupKey(fingerprint), PENDING, Duration.ofSeconds(windowSec)));
    }

    /** Points the claimed fingerprint at the created (or already open) alert. No-op if the key expired. */
    public void bindAlert(String fingerprint, long alertId) {
        redis.execute(BIND, List.of(dedupKey(fingerprint)), Long.toString(alertId));
    }

    /** Gives up a claim after alert creation failed, so the next event can try again. */
    public void releaseClaim(String fingerprint) {
        redis.execute(RELEASE_PENDING, List.of(dedupKey(fingerprint)), PENDING);
    }

    /** Forgets the group after its alert was resolved: the next matching event opens a new alert. */
    public void forget(String fingerprint) {
        redis.delete(List.of(dedupKey(fingerprint), countKey(fingerprint)));
    }

    /** Counts one repeat of the group; the {@link OccurrenceFlusher} adds it to the alert. */
    public void countRepeat(String fingerprint, int windowSec) {
        redis.execute(COUNT_REPEAT, List.of(countKey(fingerprint), DIRTY_SET),
                Long.toString(windowSec + COUNTER_GRACE_SEC), fingerprint);
    }

    /**
     * The alert a fingerprint is bound to.
     *
     * @return the alert id; empty if the key is absent; {@link Binding#pending()} while the alert is being created
     */
    public Binding binding(String fingerprint) {
        String value = redis.opsForValue().get(dedupKey(fingerprint));
        if (value == null) {
            return Binding.ABSENT;
        }
        if (PENDING.equals(value)) {
            return Binding.PENDING;
        }
        return new Binding(OptionalLong.of(Long.parseLong(value)), false);
    }

    /** Takes up to {@code max} dirty fingerprints ({@code SPOP dirty:fp max}). */
    public List<String> popDirty(int max) {
        List<String> popped = redis.opsForSet().pop(DIRTY_SET, max);
        return popped == null ? List.of() : popped;
    }

    /** Puts fingerprints back into the dirty set, e.g. while their alert is still being created. */
    public void markDirty(String fingerprint) {
        redis.opsForSet().add(DIRTY_SET, fingerprint);
    }

    /** Reads and resets the repeat counter ({@code GETDEL cnt:{fp}}); 0 if absent. */
    public long takeCount(String fingerprint) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(countKey(fingerprint))).map(Long::parseLong).orElse(0L);
    }

    static String dedupKey(String fingerprint) {
        return "dedup:" + fingerprint;
    }

    static String countKey(String fingerprint) {
        return "cnt:" + fingerprint;
    }

    /**
     * State of {@code dedup:{fp}}.
     *
     * @param alertId the bound alert, if any
     * @param pending whether the alert is still being created
     */
    public record Binding(OptionalLong alertId, boolean pending) {

        static final Binding ABSENT = new Binding(OptionalLong.empty(), false);
        static final Binding PENDING = new Binding(OptionalLong.empty(), true);
    }
}
