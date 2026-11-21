package com.hackathon.alerting.ingest.recent;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * STEP-0 STUB of {@link RecentEvents} (owner Role 1): a bounded in-memory buffer of this instance only.
 * Role 1 replaces the body with Redis {@code LPUSH recent:events} + {@code LTRIM 0 999} / {@code LRANGE}.
 */
@Component
public class InMemoryRecentEvents implements RecentEvents {

    private final Deque<JsonNode> events = new ArrayDeque<>(CAPACITY);

    @Override
    public synchronized void add(JsonNode rawPayload) {
        if (events.size() == CAPACITY) {
            events.removeLast();
        }
        events.addFirst(rawPayload);
    }

    @Override
    public synchronized List<JsonNode> latest(int limit) {
        List<JsonNode> result = new ArrayList<>(Math.min(limit, events.size()));
        Iterator<JsonNode> it = events.iterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(it.next());
        }
        return result;
    }
}
