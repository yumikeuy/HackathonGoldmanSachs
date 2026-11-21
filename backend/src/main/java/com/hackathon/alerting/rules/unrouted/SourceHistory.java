package com.hackathon.alerting.rules.unrouted;

import com.hackathon.alerting.rules.cache.TeamRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which teams the events of each source were routed to — Redis hash {@code src:teams:{source}}, field = team id,
 * value = number of events. Fed by the router for every routed event; read by {@link TeamSuggestionService}.
 *
 * <p>The history is a hint, not data the pipeline depends on: Redis errors here are logged and ignored, never thrown
 * into event processing. One hash per source and one field per team keep it small; it has no TTL.
 */
@Component
public class SourceHistory {

    private static final Logger log = LoggerFactory.getLogger(SourceHistory.class);

    private final StringRedisTemplate redis;

    public SourceHistory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Counts one event of {@code sourceName} for each of the teams it was routed to. */
    public void record(String sourceName, List<TeamRoute> teams) {
        try {
            for (TeamRoute team : teams) {
                redis.opsForHash().increment(key(sourceName), Long.toString(team.teamId()), 1);
            }
        } catch (RuntimeException e) {
            log.debug("Could not record source history for {}", sourceName, e);
        }
    }

    /** Team id → number of events of the source routed to it; empty if unknown or Redis is unavailable. */
    public Map<Long, Long> teamCounts(String sourceName) {
        Map<Long, Long> counts = new HashMap<>();
        try {
            redis.opsForHash().entries(key(sourceName))
                    .forEach((team, count) -> counts.put(Long.parseLong((String) team), Long.parseLong((String) count)));
        } catch (RuntimeException e) {
            log.debug("Could not read source history for {}", sourceName, e);
        }
        return counts;
    }

    static String key(String sourceName) {
        return "src:teams:" + sourceName;
    }
}
