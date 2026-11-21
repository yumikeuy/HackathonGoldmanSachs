package com.hackathon.alerting.common.condition;

import tools.jackson.databind.JsonNode;

import java.util.function.Predicate;

/**
 * Contract (AGENTS.md §8, owner Role 3): compile a condition tree (AGENTS.md §5) into a predicate over raw events.
 *
 * <p>Compile once when loading configuration into a cache, then evaluate per event — evaluation must not
 * parse anything or touch the database. Compiling is also the validation step for {@code POST/PUT}
 * of teams, rules and runbooks.
 */
public interface ConditionCompiler {

    /**
     * @param condition condition tree; {@code null} (or JSON null) compiles to "always true"
     * @param strict    exact paths and exact type comparison only (the team's {@code strict} flag)
     * @return thread-safe predicate
     * @throws InvalidConditionException if the tree is malformed
     */
    Predicate<JsonNode> compile(JsonNode condition, boolean strict);
}
