package com.hackathon.alerting.ingest.recent;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Contract (owner Role 1): the last {@value #CAPACITY} raw events across all sources (Redis {@code recent:events}).
 * Role 1 adds every accepted event at ingest; Role 2 reads them for {@code POST /api/rules/dry-run}.
 */
public interface RecentEvents {

    /** How many events are kept (AGENTS.md §3: {@code LPUSH} + {@code LTRIM 1000}). */
    int CAPACITY = 1000;

    /** Remember an accepted event; the oldest one is dropped beyond {@link #CAPACITY}. */
    void add(JsonNode rawPayload);

    /** Up to {@code limit} most recent events, newest first. */
    List<JsonNode> latest(int limit);
}
