package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.ChannelPreference;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.delivery.repository.UserRepository;
import com.hackathon.alerting.delivery.routing.UserDirectory;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.rules.ConfigChanges;
import com.hackathon.alerting.rules.cache.RoutingCache;
import com.hackathon.alerting.rules.model.Presentation;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.model.Severity;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of Role 3's acceptance scenario (roles.md §8 "Done when"):
 * upload a Markdown runbook with a condition via REST → reload cache via Redis Pub/Sub →
 * ingest an event over Kafka → alert card exposes runbook link, outbox notification contains
 * the link, audit trail records {@code RUNBOOK_ATTACHED}, and file download serves
 * {@code text/markdown}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class RunbookEndToEndIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private TeamRepository teams;

    @Autowired
    private UserRepository users;

    @Autowired
    private RuleRepository rules;

    @Autowired
    private ConfigChangedPublisher configChanged;

    @Autowired
    private RoutingCache routing;

    @Autowired
    private UserDirectory userDirectory;

    @Autowired
    private RunbookCache runbookCache;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadedMarkdownRunbookAttachesToAlertOutboxAuditAndDownloadsAsMarkdown() throws Exception {
        String desk = "rb-e2e-" + UUID.randomUUID();
        Team team = new Team("Team " + desk);
        team.setCondition(json.readTree("{\"path\":\"desk\",\"op\":\"==\",\"value\":\"" + desk + "\"}"));
        team = teams.save(team);
        long teamId = team.getId();

        User onCall = new User(team, "On-call Engineer");
        onCall.setEmail(desk + "@bank.com");
        onCall.setChannelPreferences(List.of(ChannelPreference.anyTime(Channel.EMAIL)));
        onCall = users.save(onCall);
        long userId = onCall.getId();

        Rule rule = new Rule(team, "Rejected trades E2E");
        rule.setCondition(json.readTree("{\"path\":\"type\",\"op\":\"==\",\"value\":\"TRADE_REJECTED\"}"));
        rule.setAssignedSeverity(Severity.HIGH);
        rule.setChannels(List.of(Channel.EMAIL, Channel.UI));
        rule.setPresentation(new Presentation("Rejected {{trade.id}}"));
        rule = rules.save(rule);
        long ruleId = rule.getId();

        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.TEAMS,
                () -> routing.current().teams().stream().anyMatch(t -> t.teamId() == teamId), TIMEOUT);
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.RULES,
                () -> routing.current().teams().stream().flatMap(t -> t.rules().stream()).anyMatch(r -> r.id() == ruleId), TIMEOUT);
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.USERS,
                () -> userDirectory.activeMembers(teamId).stream().anyMatch(u -> u.getId() == userId), TIMEOUT);

        byte[] markdownBytes = loadBytes("runbooks/trade-rejection.md");
        MockMultipartFile mdPart = new MockMultipartFile("file", "trade-rejection.md", "text/markdown", markdownBytes);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/runbooks")
                        .file(mdPart)
                        .param("teamId", String.valueOf(teamId))
                        .param("condition", "{\"path\":\"type\",\"op\":\"==\",\"value\":\"TRADE_REJECTED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("trade-rejection.md"))
                .andReturn();
        long runbookId = json.readTree(uploadResult.getResponse().getContentAsString()).get("id").asLong();

        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.RUNBOOKS,
                () -> runbookCache.current().findById(runbookId).isPresent(), TIMEOUT);

        ObjectNode payload = (ObjectNode) json.readTree(loadBytes("events/rates-trade-rejected.json"));
        payload.put("desk", desk);
        EventEnvelope envelope = EventEnvelope.create(SourceType.HTTP, "payments-monitoring", payload, Clock.systemUTC());
        kafka.send(KafkaTopics.EVENTS_INGEST, envelope.sourceName(), json.writeValueAsString(envelope)).join();

        await().atMost(TIMEOUT).until(() ->
                !jdbc.queryForList("select id from alerts where rule_id = ?", Long.class, ruleId).isEmpty());
        long alertId = jdbc.queryForObject("select id from alerts where rule_id = ?", Long.class, ruleId);

        String expectedFileUrl = "/api/runbooks/" + runbookId + "/file";

        // 1. Alert card includes the attached runbook link.
        mockMvc.perform(get("/api/alerts/" + alertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runbookId").value(runbookId))
                .andExpect(jsonPath("$.runbook.id").value(runbookId))
                .andExpect(jsonPath("$.runbook.fileName").value("trade-rejection.md"))
                .andExpect(jsonPath("$.runbook.fileUrl").value(expectedFileUrl));

        // 2. Outbox notification payload includes the runbook download URL.
        Map<String, Object> emailOutbox = jdbc.queryForMap(
                "select payload::text as payload_json from delivery_outbox where alert_id = ? and channel = 'EMAIL'",
                alertId);
        JsonNode emailPayload = json.readTree((String) emailOutbox.get("payload_json"));
        assertThat(emailPayload.get("runbookUrl").asString()).endsWith(expectedFileUrl);
        assertThat(emailPayload.get("text").asString()).contains(expectedFileUrl);

        // 3. Audit trail records RUNBOOK_ATTACHED with system actor and match reason.
        MvcResult auditResult = mockMvc.perform(get("/api/alerts/" + alertId + "/audit"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auditEntries = json.readTree(auditResult.getResponse().getContentAsString());
        assertThat(auditEntries.values()).anySatisfy(entry -> {
            assertThat(entry.get("actor").asString()).isEqualTo("system");
            assertThat(entry.get("actionType").asString()).isEqualTo("RUNBOOK_ATTACHED");
            assertThat(entry.get("details").get("runbookId").asLong()).isEqualTo(runbookId);
            assertThat(entry.get("details").get("fileName").asString()).isEqualTo("trade-rejection.md");
            assertThat(entry.get("details").get("reason").asString()).isEqualTo("team");
        });

        // 4. Download returns exact bytes with Content-Type: text/markdown.
        mockMvc.perform(get(expectedFileUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_MARKDOWN))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"trade-rejection.md\"; filename*=UTF-8''trade-rejection.md"))
                .andExpect(content().bytes(markdownBytes));

        // 5. YAML runbook download serves application/yaml (scoped to this test team and deleted afterwards).
        byte[] yamlBytes = loadBytes("runbooks/high-cpu.yaml");
        MockMultipartFile yamlPart = new MockMultipartFile("file", "high-cpu.yaml", "application/yaml", yamlBytes);
        MvcResult yamlUpload = mockMvc.perform(multipart("/api/runbooks")
                        .file(yamlPart)
                        .param("teamId", String.valueOf(teamId)))
                .andExpect(status().isCreated())
                .andReturn();
        long yamlId = json.readTree(yamlUpload.getResponse().getContentAsString()).get("id").asLong();
        try {
            mockMvc.perform(get("/api/runbooks/" + yamlId + "/file"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_YAML))
                    .andExpect(content().bytes(yamlBytes));
        } finally {
            mockMvc.perform(delete("/api/runbooks/" + yamlId)).andExpect(status().isNoContent());
        }
    }

    private static byte[] loadBytes(String resourcePath) throws IOException {
        try (InputStream in = RunbookEndToEndIntegrationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as(resourcePath).isNotNull();
            return in.readAllBytes();
        }
    }
}
