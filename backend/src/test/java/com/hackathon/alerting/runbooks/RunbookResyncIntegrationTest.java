package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.rules.ConfigChanges;
import com.hackathon.alerting.rules.cache.RoutingCache;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Open alerts follow runbook changes ({@code RunbookResync}): a better new runbook replaces the pick, an edited
 * condition moves the alert, a rule's "always attach" wins, a deleted runbook is replaced or removed — each change
 * audited. Resolved alerts keep their runbook. Same context as the other runbook / pipeline integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class RunbookResyncIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private MockMvcTester mvc;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private RuleRepository rules;
    @Autowired
    private RoutingCache routing;
    @Autowired
    private ConfigChangedPublisher configChanged;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private JsonMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void openAlertsFollowRunbookChangesAndResolvedAlertsKeepTheirs() throws Exception {
        String desk = "resync-" + UUID.randomUUID();
        Team team = new Team("Team " + desk);
        team.setCondition(json.readTree("{\"path\":\"desk\",\"op\":\"==\",\"value\":\"" + desk + "\"}"));
        team = teams.save(team);
        Rule rule = rules.save(new Rule(team, "Resync rule"));
        long ruleId = rule.getId();
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.RULES, () -> routing.current().teams().stream()
                .flatMap(t -> t.rules().stream()).anyMatch(r -> r.id() == ruleId), TIMEOUT);

        send(desk, "A");
        long alert = awaitAlert(ruleId);
        assertThat(runbookOf(alert)).as("no runbook yet").isNull();

        // 1. a new matching team runbook is attached to the open alert
        long teamRunbook = upload(team.getId(), "{\"path\":\"kind\",\"op\":\"==\",\"value\":\"A\"}", "team.md");
        assertThat(runbookOf(alert)).isEqualTo(teamRunbook);
        JsonNode attached = lastAudit(alert);
        assertThat(attached.get("actionType").asString()).isEqualTo("RUNBOOK_ATTACHED");
        assertThat(attached.get("details").get("reason").asString()).isEqualTo("team");
        assertThat(attached.get("details").get("trigger").asString()).isEqualTo("runbook " + teamRunbook + " created");

        // 2. a matching global runbook does not replace it: the team's runbook comes first
        long globalRunbook = upload(null, "{\"path\":\"desk\",\"op\":\"==\",\"value\":\"" + desk + "\"}", "global.md");
        assertThat(runbookOf(alert)).isEqualTo(teamRunbook);

        // 3. the team runbook no longer matches → the alert moves to the global one
        update(teamRunbook, team.getId(), "{\"path\":\"kind\",\"op\":\"==\",\"value\":\"B\"}");
        assertThat(runbookOf(alert)).isEqualTo(globalRunbook);
        assertThat(lastAudit(alert).get("details").get("previousRunbookId").asLong()).isEqualTo(teamRunbook);

        // 4. the rule's "always attach" wins over both; removing it goes back to the global one
        setOverride(ruleId, team.getId(), teamRunbook);
        assertThat(runbookOf(alert)).isEqualTo(teamRunbook);
        assertThat(lastAudit(alert).get("details").get("reason").asString()).isEqualTo("override");
        setOverride(ruleId, team.getId(), null);
        assertThat(runbookOf(alert)).isEqualTo(globalRunbook);

        // 5. deleting the attached runbook detaches it (nothing else matches), visibly
        assertThat(mvc.delete().uri("/api/runbooks/" + globalRunbook)).hasStatus(204);
        assertThat(runbookOf(alert)).isNull();
        JsonNode detached = lastAudit(alert);
        assertThat(detached.get("actionType").asString()).isEqualTo("RUNBOOK_DETACHED");
        assertThat(detached.get("details").get("previousRunbookId").asLong()).isEqualTo(globalRunbook);

        // 6. a resolved alert keeps what it had, even when a matching runbook appears
        assertThat(mvc.post().uri("/api/alerts/" + alert + "/resolve")).hasStatusOk();
        upload(team.getId(), "{\"path\":\"kind\",\"op\":\"==\",\"value\":\"A\"}", "after-resolve.md");
        assertThat(runbookOf(alert)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private void send(String desk, String kind) {
        JsonNode payload = json.readTree("{\"desk\":\"" + desk + "\",\"kind\":\"" + kind + "\"}");
        EventEnvelope envelope = EventEnvelope.create(SourceType.HTTP, "resync-test", payload, Clock.systemUTC());
        kafka.send(KafkaTopics.EVENTS_INGEST, envelope.sourceName(), json.writeValueAsString(envelope)).join();
    }

    private long awaitAlert(long ruleId) {
        await().atMost(TIMEOUT).until(() -> !jdbc.queryForList("select id from alerts where rule_id = ?", Long.class, ruleId).isEmpty());
        return jdbc.queryForObject("select id from alerts where rule_id = ?", Long.class, ruleId);
    }

    /** Uploads a runbook ({@code teamId} null = global) with a condition and returns its id. */
    private long upload(Long teamId, String condition, String fileName) throws Exception {
        var request = mvc.post().uri("/api/runbooks").multipart()
                .file(new MockMultipartFile("file", fileName, "text/markdown", "# Steps".getBytes(StandardCharsets.UTF_8)))
                .param("condition", condition);
        if (teamId != null) {
            request = request.param("teamId", teamId.toString());
        }
        return body(request.exchange()).get("id").asLong();
    }

    private void update(long runbookId, long teamId, String condition) {
        assertThat(mvc.put().uri("/api/runbooks/" + runbookId).multipart()
                .param("teamId", Long.toString(teamId)).param("condition", condition)).hasStatusOk();
    }

    private void setOverride(long ruleId, long teamId, Long runbookId) {
        String override = runbookId == null ? "null" : runbookId.toString();
        assertThat(mvc.put().uri("/api/rules/" + ruleId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Resync rule\",\"teamId\":" + teamId + ",\"overrideRunbookId\":" + override + "}"))
                .hasStatusOk();
    }

    private Long runbookOf(long alertId) throws Exception {
        JsonNode runbook = body(mvc.get().uri("/api/alerts/" + alertId).exchange()).get("runbookId");
        return runbook == null || runbook.isNull() ? null : runbook.asLong();
    }

    private JsonNode lastAudit(long alertId) throws Exception {
        JsonNode trail = body(mvc.get().uri("/api/alerts/" + alertId + "/audit").exchange());
        return trail.get(trail.size() - 1);
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus2xxSuccessful();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
