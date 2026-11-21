package com.hackathon.alerting.rules;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.rules.model.Presentation;
import com.hackathon.alerting.rules.cache.RoutingCache;
import com.hackathon.alerting.rules.cache.RoutingTable;
import com.hackathon.alerting.rules.model.Rule;
import io.micrometer.core.instrument.MeterRegistry;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Role 2 end to end on real infrastructure: envelopes on {@code events.ingest} → alerts in Postgres, plus the
 * rules and alerts REST API. Every test works with its own team (unique {@code desk} value), so tests sharing
 * the context and the topic never see each other's alerts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class AlertPipelineIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JsonMapper json;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private RuleRepository rules;

    @Autowired
    private ConfigChangedPublisher configChanged;

    @Autowired
    private RoutingCache routing;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvcTester mvc;

    private String desk;
    private Team team;

    @BeforeEach
    void createTeam() {
        desk = "desk-" + UUID.randomUUID();
        team = new Team("Team " + desk);
        team.setCondition(json.readTree("{\"path\":\"desk\",\"op\":\"==\",\"value\":\"" + desk + "\"}"));
        team = teams.save(team);
        long teamId = team.getId();
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.TEAMS,
                () -> routing.current().teams().stream().anyMatch(t -> t.teamId() == teamId), TIMEOUT);
    }

    // ------------------------------------------------------------------ processing

    @Test
    void burstOfIdenticalEventsBecomesOneAlertWithCounter() {
        Rule rule = rule("Rejected trades", r -> r.setGroupByPaths(List.of("instrument")));
        double createdBefore = counter("alerting.alerts.created", "severity", "MEDIUM");
        double repeatsBefore = meters.get("alerting.alerts.repeats").counter().count();

        sendAll(IntStream.range(0, 500).mapToObj(i -> event(Map.of("instrument", "EUR-IRS-10Y", "seq", i))).toList());

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(alertsOf(rule)).singleElement().satisfies(alert -> {
                    assertThat(alert.get("occurrences_count")).isEqualTo(500);
                    assertThat(alert.get("status")).isEqualTo("OPEN");
                    assertThat(alert.get("severity")).isEqualTo("MEDIUM");
                }));
        long alertId = ((Number) alertsOf(rule).getFirst().get("id")).longValue();
        assertThat(auditActions(alertId)).containsExactly("ALERT_CREATED");
        assertThat(counter("alerting.alerts.created", "severity", "MEDIUM")).isGreaterThanOrEqualTo(createdBefore + 1);
        assertThat(meters.get("alerting.alerts.repeats").counter().count()).isGreaterThanOrEqualTo(repeatsBefore + 499);
        assertThat(meters.get("alerting.processing.delay").timer().count()).isPositive();
    }

    @Test
    void groupByPathsSplitAlertsAndTitleIsRendered() {
        Rule rule = rule("Rejected", r -> {
            r.setGroupByPaths(List.of("instrument"));
            r.setPresentation(new Presentation("Rejected {{instrument}}"));
        });

        sendAll(List.of(event(Map.of("instrument", "EUR")), event(Map.of("instrument", "USD")), event(Map.of("instrument", "EUR"))));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alertsOf(rule))
                .extracting(a -> a.get("title") + "=" + a.get("occurrences_count"))
                .containsExactlyInAnyOrder("Rejected EUR=2", "Rejected USD=1"));
    }

    @Test
    void resolveConditionClosesTheAlertAndTheNextEventOpensANewOne() {
        Rule rule = rule("Workflow failed", r -> {
            r.setCondition(json.readTree("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}"));
            r.setResolveCondition(json.readTree("{\"path\":\"status\",\"op\":\"==\",\"value\":\"OK\"}"));
        });

        send(event(Map.of("status", "FAILED")));
        await().atMost(TIMEOUT).until(() -> alertsOf(rule).size() == 1);
        double resolvedByRuleBefore = counter("alerting.alerts.resolved", "by", "rule");
        send(event(Map.of("status", "OK")));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alertsOf(rule)).singleElement()
                .satisfies(a -> assertThat(a.get("status")).isEqualTo("RESOLVED")));
        long resolvedId = ((Number) alertsOf(rule).getFirst().get("id")).longValue();
        assertThat(auditActions(resolvedId)).containsExactly("ALERT_CREATED", "AUTO_CLOSE");
        assertThat(counter("alerting.alerts.resolved", "by", "rule")).isGreaterThanOrEqualTo(resolvedByRuleBefore + 1);
        send(event(Map.of("status", "FAILED")));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alertsOf(rule))
                .extracting(a -> a.get("status")).containsExactlyInAnyOrder("RESOLVED", "OPEN"));
    }

    @Test
    void redeliveredEventIsCountedOnce() {
        Rule rule = rule("Any", r -> { });
        EventEnvelope envelope = event(Map.of("x", 1));

        sendAll(List.of(envelope, envelope, envelope));
        send(event(Map.of("x", 2)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(alertsOf(rule)).singleElement()
                .satisfies(a -> assertThat(a.get("occurrences_count")).isEqualTo(2)));
    }

    @Test
    void eventOfNoTeamBecomesAnUnroutedAlertAndIsStored() {
        String source = "legacy-" + UUID.randomUUID();
        JsonNode payload = json.readTree("{\"Header\":{\"MsgType\":\"DealException\"},\"Body\":{\"Text\":\"x\"}}");

        sendAll(List.of(envelope(source, payload), envelope(source, payload)));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(jdbc.queryForList("select severity, team_id, rule_id, occurrences_count from alerts where title = ?",
                    "Unrouted event from " + source)).singleElement().satisfies(a -> {
                assertThat(a.get("severity")).isEqualTo("UNKNOWN");
                assertThat(a.get("team_id")).isNull();
                assertThat(a.get("rule_id")).isNull();
                assertThat(a.get("occurrences_count")).isEqualTo(2);
            });
            assertThat(jdbc.queryForObject("select count(*) from events where source_name = ? and status = 'UNROUTED'",
                    Integer.class, source)).isEqualTo(2);
        });
    }

    // ------------------------------------------------------------------ REST

    @Test
    void ruleCreatedThroughTheApiTakesEffectWithoutRestart() throws Exception {
        MvcTestResult created = postJson("/api/rules", """
                {"name":"Big trades","teamId":%d,"assignedSeverity":"HIGH","groupMode":"per_window",
                 "condition":{"path":"type","op":"==","value":"BIG"},"presentation":{"title":"Big {{id}}"}}
                """.formatted(team.getId()));
        assertThat(created).hasStatus(201);
        JsonNode rule = body(created);
        assertThat(rule.get("groupMode").asString()).isEqualTo("per_window");
        assertThat(rule.get("channels")).isEqualTo(json.readTree("[\"UI\"]"));
        long ruleId = rule.get("id").asLong();
        // No re-publishing here: the API's own config-change notification must make the rule effective.
        await().atMost(TIMEOUT).until(() -> hasRule(routing.current(), ruleId));

        send(event(Map.of("type", "BIG", "id", "T-1")));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "select title, severity from alerts where rule_id = ?", ruleId)).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("title", "Big T-1").containsEntry("severity", "HIGH")));
        assertThat(mvc.get().uri("/api/rules?teamId=" + team.getId())).hasStatusOk()
                .bodyJson().extractingPath("$[0].name").isEqualTo("Big trades");
    }

    @Test
    void invalidRulesAreRejectedWithProblemDetails() throws Exception {
        assertThat(postJson("/api/rules", """
                {"name":"x","teamId":%d,"condition":{"path":"a","op":"no-such-op","value":1}}""".formatted(team.getId())))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString().startsWith("condition:");
        assertThat(postJson("/api/rules", """
                {"name":"x","teamId":%d,"assignedSeverity":"UNKNOWN"}""".formatted(team.getId()))).hasStatus(400);
        assertThat(postJson("/api/rules", """
                {"name":"","teamId":%d}""".formatted(team.getId()))).hasStatus(400);
        assertThat(postJson("/api/rules", """
                {"name":"x","teamId":999999999}""")).hasStatus(400);
        assertThat(mvc.get().uri("/api/rules/999999999")).hasStatus(404);
    }

    @Test
    void ruleWithAlertsCannotBeDeletedButCanBeDeactivated() {
        Rule rule = rule("To delete", r -> { });
        send(event(Map.of("k", "v")));
        await().atMost(TIMEOUT).until(() -> alertsOf(rule).size() == 1);

        assertThat(mvc.delete().uri("/api/rules/" + rule.getId())).hasStatus(409);

        Rule unused = rule("Unused", r -> r.setCondition(json.readTree("{\"path\":\"never\",\"op\":\"==\",\"value\":true}")));
        assertThat(mvc.delete().uri("/api/rules/" + unused.getId())).hasStatus(204);
    }

    @Test
    void feedAcknowledgeAndResolve() throws Exception {
        Rule rule = rule("Feed", r -> r.setGroupByPaths(List.of("n")));
        sendAll(IntStream.range(0, 3).mapToObj(i -> event(Map.of("n", i))).toList());
        await().atMost(TIMEOUT).until(() -> alertsOf(rule).size() == 3);

        JsonNode page1 = body(mvc.get().uri("/api/alerts?teamId=" + team.getId() + "&limit=2").exchange());
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("items").get(0).get("ruleName").asString()).isEqualTo("Feed");
        JsonNode page2 = body(mvc.get().uri("/api/alerts?teamId=" + team.getId() + "&limit=2&cursor="
                + page1.get("nextCursor").asString()).exchange());
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.get("nextCursor").isNull()).isTrue();

        long id = page1.get("items").get(0).get("id").asLong();
        assertThat(postJson("/api/alerts/" + id + "/ack", "{\"actor\":\"anna@bank.com\"}")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("ACKED");
        assertThat(mvc.post().uri("/api/alerts/" + id + "/resolve")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("RESOLVED");
        assertThat(mvc.post().uri("/api/alerts/" + id + "/resolve")).as("idempotent").hasStatusOk();
        assertThat(mvc.post().uri("/api/alerts/" + id + "/ack")).hasStatus(409);
        assertThat(auditActions(id)).containsExactly("ALERT_CREATED", "ACK", "RESOLVE");
        assertThat(jdbc.queryForObject("select actor from audit_logs where alert_id = ? and action_type = 'ACK'",
                String.class, id)).isEqualTo("anna@bank.com");

        assertThat(mvc.get().uri("/api/alerts/" + id)).hasStatusOk().bodyJson()
                .extractingPath("$.samplePayload.desk").isEqualTo(desk);
        assertThat(mvc.get().uri("/api/alerts?teamId=" + team.getId() + "&status=RESOLVED")).hasStatusOk()
                .bodyJson().extractingPath("$.items.length()").isEqualTo(1);
        assertThat(mvc.get().uri("/api/alerts")).as("teamId or unrouted required").hasStatus(400);
        assertThat(mvc.get().uri("/api/alerts?teamId=1&cursor=garbage")).hasStatus(400);
        assertThat(mvc.get().uri("/api/alerts/999999999")).hasStatus(404);
    }

    @Test
    void unroutedAlertGetsTeamSuggestionsFromNearMissesAndSourceHistory() throws Exception {
        Team deals = new Team("Deals " + desk);
        deals.setCondition(json.readTree("{\"and\":[{\"path\":\"desk\",\"op\":\"==\",\"value\":\"" + desk + "\"},"
                + "{\"path\":\"kind\",\"op\":\"==\",\"value\":\"DEAL\"}]}"));
        deals = teams.save(deals);
        long dealsId = deals.getId();
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.TEAMS,
                () -> routing.current().teams().stream().anyMatch(t -> t.teamId() == dealsId), TIMEOUT);
        String source = "deals-" + UUID.randomUUID();

        // History: one routed event of this source; it matches both of this test's teams.
        send(envelope(source, json.readTree("{\"desk\":\"" + desk + "\",\"kind\":\"DEAL\"}")));
        await().atMost(TIMEOUT).until(() -> Boolean.TRUE.equals(redis.hasKey("src:teams:" + source)));
        // The unrouted one: no desk, so no team matches; "kind" alone is a near miss for the Deals team.
        send(envelope(source, json.readTree("{\"kind\":\"DEAL\",\"ref\":\"X-1\"}")));
        String title = "Unrouted event from " + source;
        await().atMost(TIMEOUT).until(() -> !jdbc.queryForList("select id from alerts where title = ?", Long.class, title).isEmpty());
        long unroutedId = jdbc.queryForObject("select id from alerts where title = ?", Long.class, title);

        JsonNode suggestions = body(mvc.get().uri("/api/alerts/" + unroutedId + "/team-suggestions").exchange());

        assertThat(suggestions.get(0).get("teamId").asLong()).isEqualTo(dealsId);
        assertThat(suggestions.get(0).get("score").asDouble()).as("0.7 × 1/2 conditions + 0.3 × 1/2 history").isEqualTo(0.5);
        assertThat(suggestions.get(0).get("reason").asString())
                .isEqualTo("1 of 2 conditions match; 1 event from source " + source + " was routed to this team");
        assertThat(suggestions.findValues("teamId")).extracting(JsonNode::asLong).contains(team.getId());

        long routedId = jdbc.queryForObject("select id from alerts where team_id is not null order by id limit 1", Long.class);
        assertThat(mvc.get().uri("/api/alerts/" + routedId + "/team-suggestions")).hasStatusOk().bodyJson().isEqualTo("[]");
        assertThat(mvc.get().uri("/api/alerts/999999999/team-suggestions")).hasStatus(404);
    }

    // ------------------------------------------------------------------ helpers

    /** Saves an active rule of this test's team and reloads the routing cache. */
    private Rule rule(String name, Consumer<Rule> customizer) {
        Rule rule = new Rule(team, name);
        customizer.accept(rule);
        rule = rules.save(rule);
        long ruleId = rule.getId();
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.RULES, () -> hasRule(routing.current(), ruleId), TIMEOUT);
        return rule;
    }

    private List<String> auditActions(long alertId) {
        return jdbc.queryForList("select action_type from audit_logs where alert_id = ? order by created_at, id", String.class, alertId);
    }

    private double counter(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).counter().count();
    }

    private static boolean hasRule(RoutingTable table, long ruleId) {
        return table.teams().stream().flatMap(t -> t.rules().stream()).anyMatch(r -> r.id() == ruleId);
    }

    private EventEnvelope event(Map<String, Object> fields) {
        JsonNode payload = json.valueToTree(fields);
        ((ObjectNode) payload).put("desk", desk);
        return envelope("test-source", payload);
    }

    private static EventEnvelope envelope(String source, JsonNode payload) {
        return EventEnvelope.create(SourceType.HTTP, source, payload, Clock.systemUTC());
    }

    private void send(EventEnvelope envelope) {
        sendAll(List.of(envelope));
    }

    private void sendAll(List<EventEnvelope> envelopes) {
        CompletableFuture.allOf(envelopes.stream()
                .map(e -> kafka.send(KafkaTopics.EVENTS_INGEST, e.sourceName(), json.writeValueAsString(e)))
                .toArray(CompletableFuture[]::new)).join();
    }

    private List<Map<String, Object>> alertsOf(Rule rule) {
        return jdbc.queryForList("select id, title, status, severity, occurrences_count from alerts where rule_id = ? order by id",
                rule.getId());
    }

    private MvcTestResult postJson(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus2xxSuccessful();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
