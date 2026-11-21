package com.hackathon.alerting.rules.cache;

import com.hackathon.alerting.rules.model.Rule;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * An active rule ready for the hot path: the rule (a <b>detached, read-only</b> entity with its team
 * initialized) plus its compiled conditions.
 *
 * <p>Never modify {@link #rule()}: the same instance is shared by all consumer threads until the next reload.
 *
 * @param rule             the rule; {@code rule.getTeam()} is initialized
 * @param strict           the team's strict flag, used for conditions and field lookups of this rule
 * @param condition        when the rule fires ({@code rules.condition}; {@code null} there compiles to "always")
 * @param resolveCondition when an event closes the rule's open alert, or {@code null} if it never does
 */
public record RuleRoute(Rule rule, boolean strict, Predicate<JsonNode> condition, Predicate<JsonNode> resolveCondition) {

    public RuleRoute {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(condition, "condition");
    }

    public long id() {
        return rule.getId();
    }

    public long teamId() {
        return rule.getTeam().getId();
    }

    /** Whether the event should produce (or repeat) an alert of this rule. */
    public boolean fires(JsonNode event) {
        return Predicates.safeTest(condition, event, this);
    }

    /** Whether the event resolves this rule's open alert. */
    public boolean resolves(JsonNode event) {
        return resolveCondition != null && Predicates.safeTest(resolveCondition, event, this);
    }
}
