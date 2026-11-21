package com.hackathon.alerting.dev;

import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.ingest.model.Source;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.repository.SourceRepository;
import com.hackathon.alerting.ingest.source.SourceCache;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code POST /api/dev/generate} end to end: generated events go through the real ingest path into Kafka, are processed
 * and grouped into an alert. Same context as {@code AlertPipelineIntegrationTest} (same annotations).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class GeneratorIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private SourceRepository sources;

    @Autowired
    private SourceCache sourceCache;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private RuleRepository rules;

    @Autowired
    private RoutingCache routing;

    @Autowired
    private ConfigChangedPublisher configChanged;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Test
    void generatedEventsFlowThroughIngestAndBecomeOneGroupedAlert() {
        String desk = "gen-" + UUID.randomUUID();
        String source = activeSource();
        Rule rule = ruleForDesk(desk);

        MvcTestResult accepted = post("""
                {"source":"%s","count":25,"ratePerSecond":500,
                 "template":{"desk":"%s","type":"GENERATED","n":"{{seq}}","ccy":"{{oneOf:EUR|USD}}"}}""".formatted(source, desk));

        assertThat(accepted).hasStatus(202).bodyJson().extractingPath("$.count").isEqualTo(25);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "select occurrences_count from alerts where rule_id = ?", Integer.class, rule.getId())).containsExactly(25));
        assertThat(jdbc.queryForObject("select sample_payload->>'type' from alerts where rule_id = ?", String.class, rule.getId()))
                .isEqualTo("GENERATED");
    }

    @Test
    void rejectsInvalidRequests() {
        String source = activeSource();

        assertThat(post("{\"source\":\"no-such-source\"}")).hasStatus(400);
        assertThat(post("{\"source\":\"" + source + "\",\"count\":0}")).hasStatus(400);
        assertThat(post("{\"source\":\"" + source + "\",\"ratePerSecond\":5000}")).hasStatus(400);
        assertThat(post("{\"source\":\"" + source + "\",\"template\":{\"a\":\"{{nope}}\"}}")).hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("unknown placeholder");
    }

    /** An active HTTP source, visible in Role 1's source cache. */
    private String activeSource() {
        String name = "gen-src-" + UUID.randomUUID();
        sources.save(new Source(name, SourceType.HTTP, null));
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.SOURCES, () -> sourceCache.findActive(name).isPresent(), TIMEOUT);
        return name;
    }

    /** A team owning events with this desk and a catch-all rule, visible in the routing cache. */
    private Rule ruleForDesk(String desk) {
        Team team = new Team("Team " + desk);
        team.setCondition(json.readTree("{\"path\":\"desk\",\"op\":\"==\",\"value\":\"" + desk + "\"}"));
        Team saved = teams.save(team);
        Rule rule = rules.save(new Rule(saved, "Generated"));
        rule.setGroupByPaths(List.of());
        ConfigChanges.publishAndAwait(configChanged, ConfigKinds.RULES, () -> routing.current().teams().stream()
                .flatMap(t -> t.rules().stream()).anyMatch(r -> r.id() == rule.getId()), TIMEOUT);
        return rule;
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri("/api/dev/generate").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
