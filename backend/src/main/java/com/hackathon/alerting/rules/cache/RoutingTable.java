package com.hackathon.alerting.rules.cache;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Immutable snapshot of all teams and their active rules. {@link RoutingCache} swaps the whole table on
 * reload, so a batch of events is always routed against one consistent configuration.
 *
 * @param teams teams in id order
 */
public record RoutingTable(List<TeamRoute> teams) {

    public static final RoutingTable EMPTY = new RoutingTable(List.of());

    public RoutingTable {
        teams = List.copyOf(teams);
    }

    /** Every team the event belongs to — an event may match several teams (multi-match). */
    public List<TeamRoute> teamsMatching(JsonNode event) {
        return teams.stream().filter(team -> team.matches(event)).toList();
    }

    /** Number of active rules across all teams (for logs and health). */
    public int ruleCount() {
        return teams.stream().mapToInt(team -> team.rules().size()).sum();
    }
}
