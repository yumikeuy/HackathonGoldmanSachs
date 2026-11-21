package com.hackathon.alerting.rules;

import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.rules.model.Rule;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builders of unsaved entities with ids, for unit tests of the rules module. */
public final class RulesTestData {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RulesTestData() {
    }

    public static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    public static Team team(long id, String name, String condition) {
        Team team = new Team(name);
        ReflectionTestUtils.setField(team, "id", id);
        team.setCondition(condition == null ? null : json(condition));
        return team;
    }

    public static Rule rule(long id, Team team, String condition) {
        Rule rule = new Rule(team, "rule-" + id);
        ReflectionTestUtils.setField(rule, "id", id);
        rule.setCondition(condition == null ? null : json(condition));
        return rule;
    }
}
