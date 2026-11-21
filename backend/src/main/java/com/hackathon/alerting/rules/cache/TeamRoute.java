package com.hackathon.alerting.rules.cache;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A team and its active rules, ready for the hot path.
 *
 * @param teamId  team id
 * @param name    team name (for logs)
 * @param strict  the team's strict flag
 * @param matcher which events belong to the team; a team without a condition never matches (AGENTS.md §11, q.1)
 * @param rules   active rules in evaluation order ({@code priority}, then id)
 */
public record TeamRoute(long teamId, String name, boolean strict, Predicate<JsonNode> matcher, List<RuleRoute> rules) {

    public TeamRoute {
        Objects.requireNonNull(matcher, "matcher");
        rules = List.copyOf(rules);
    }

    public boolean matches(JsonNode event) {
        return Predicates.safeTest(matcher, event, this);
    }
}
