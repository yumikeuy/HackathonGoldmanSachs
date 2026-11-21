package com.hackathon.alerting.support;

import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** Small builders for persisted test data. Names are randomised so tests never collide on unique columns. */
public final class TestData {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TestEntityManager em;

    public TestData(TestEntityManager em) {
        this.em = em;
    }

    public static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    /** A 64-character fingerprint unique to this call. */
    public static String randomFingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    public Team team() {
        return em.persist(new Team("Team " + UUID.randomUUID()));
    }

    public User user(Team team) {
        User user = new User(team, "User");
        user.setEmail(UUID.randomUUID() + "@bank.com");
        return em.persist(user);
    }

    public Rule rule(Team team) {
        return em.persist(new Rule(team, "Rule"));
    }

    public Alert alert(Rule rule, Instant seenAt) {
        return em.persist(Alert.forRule(rule, randomFingerprint(), "Alert", json("{}"), seenAt));
    }
}
