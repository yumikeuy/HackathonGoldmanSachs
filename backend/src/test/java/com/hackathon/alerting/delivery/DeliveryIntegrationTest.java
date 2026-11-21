package com.hackathon.alerting.delivery;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.OutboxStatus;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.outbox.OutboxService;
import com.hackathon.alerting.delivery.outbox.OutboxWorker;
import com.hackathon.alerting.delivery.repository.OutboxTaskRepository;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.delivery.routing.UserDirectory;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.MutableClock;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Role 1 "done when": with a channel switched off messages are delivered after switching it on, none lost;
 * retries end in FAILED; more than 20 messages a minute are merged into one digest.
 * The scheduled worker is slowed down, the test drives {@link OutboxWorker#run()} and the clock itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"alerting.outbox.poll-interval=1h", "management.health.mail.enabled=false"})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class,
        DeliveryIntegrationTest.ClockConfiguration.class})
class DeliveryIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private OutboxWorker worker;
    @Autowired
    private OutboxTaskRepository outbox;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private RuleRepository rules;
    @Autowired
    private AlertRepository alerts;
    @Autowired
    private UserDirectory userDirectory;
    @Autowired
    private TransactionTemplate transaction;
    @MockitoBean
    private JavaMailSender mailSender;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
    }

    @Test
    void messagesQueuedWhileSmsIsDownAreDeliveredWhenItComesBack() {
        long teamId = createTeamWithUser(Map.of("phone", "+48600000001",
                "channelPreferences", List.of(Map.of("channel", "SMS"))));
        long alertId = createAlertAndEnqueue(teamId, List.of(Channel.SMS, Channel.UI));
        assertThat(rows(alertId)).extracting(OutboxTask::getChannel).containsExactly(Channel.UI, Channel.SMS);

        toggle(Channel.SMS);   // down
        worker.run();
        assertThat(row(alertId, Channel.UI).getStatus()).isEqualTo(OutboxStatus.SENT);
        OutboxTask sms = row(alertId, Channel.SMS);
        assertThat(sms.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(sms.getRetryCount()).isEqualTo(1);
        assertThat(sms.getLastError()).contains("down");

        clock.advance(Duration.ofSeconds(3));
        toggle(Channel.SMS);   // up again
        worker.run();
        assertThat(row(alertId, Channel.SMS).getStatus()).isEqualTo(OutboxStatus.SENT);

        JsonNode page = http().get().uri("/api/simulator/deliveries?limit=200").retrieve().body(JsonNode.class);
        assertThat(page.get("items").findValuesAsString("status")).isNotEmpty();
        assertThat(page.get("items").findValues("alertId")).extracting(JsonNode::asLong).contains(alertId);
    }

    @Test
    void retriesAreExhaustedAfterTwoFiveFifteenSixtySeconds() {
        long teamId = createTeamWithUser(Map.of("phone", "+48600000002",
                "channelPreferences", List.of(Map.of("channel", "CALL"))));
        long alertId = createAlertAndEnqueue(teamId, List.of(Channel.CALL));

        toggle(Channel.CALL);  // down for good
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                worker.run();
                clock.advance(Duration.ofSeconds(61));
            }
        } finally {
            toggle(Channel.CALL);
        }

        OutboxTask call = row(alertId, Channel.CALL);
        assertThat(call.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(call.getRetryCount()).isEqualTo(5);
    }

    @Test
    void moreThanTwentyEmailsAMinuteAreMergedIntoOneDigest() {
        long teamId = createTeamWithUser(Map.of("email", UUID.randomUUID() + "@bank.com",
                "channelPreferences", List.of(Map.of("channel", "EMAIL"))));
        List<Long> alertIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            alertIds.add(createAlertAndEnqueue(teamId, List.of(Channel.EMAIL)));
        }

        worker.run();

        verify(mailSender, times(20)).send(any(SimpleMailMessage.class));
        List<OutboxTask> emails = alertIds.stream().map(id -> row(id, Channel.EMAIL)).toList();
        assertThat(emails).allMatch(task -> task.getStatus() == OutboxStatus.SENT);
        assertThat(emails).filteredOn(task -> task.getPayload().has("merged")).singleElement()
                .satisfies(digest -> assertThat(digest.getPayload().get("merged").asInt()).isEqualTo(6));
        assertThat(emails).filteredOn(task -> task.getPayload().has("mergedInto")).hasSize(5);
    }

    @Test
    void userChangesReachTheRoutingCacheThroughRedis() {
        long teamId = createTeamWithUser(Map.of("email", UUID.randomUUID() + "@bank.com"));

        assertThat(userDirectory.activeMembers(teamId)).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private long createTeamWithUser(Map<String, Object> userFields) {
        JsonNode team = http().post().uri("/api/teams").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Team " + UUID.randomUUID())).retrieve().body(JsonNode.class);
        long teamId = team.get("id").asLong();
        Map<String, Object> user = new HashMap<>(userFields);
        user.put("teamId", teamId);
        user.put("name", "Anna");
        int status = http().post().uri("/api/users").contentType(MediaType.APPLICATION_JSON).body(user)
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(201);
        // The cache reloads after the config.changed message made its round trip through Redis.
        await().atMost(Duration.ofSeconds(10)).until(() -> !userDirectory.activeMembers(teamId).isEmpty());
        return teamId;
    }

    private long createAlertAndEnqueue(long teamId, List<Channel> channels) {
        return transaction.execute(status -> {
            Team team = teams.findById(teamId).orElseThrow();
            Rule rule = new Rule(team, "Rule");
            rule.setChannels(channels);
            rules.save(rule);
            Alert alert = alerts.save(Alert.forRule(rule, TestData.randomFingerprint(), "Trade rejected", TestData.json("{}"), clock.instant()));
            outboxService.enqueue(alert, rule);
            return alert.getId();
        });
    }

    private List<OutboxTask> rows(long alertId) {
        return transaction.execute(status -> outbox.findAllByAlertIdOrderByIdAsc(alertId));
    }

    private OutboxTask row(long alertId, Channel channel) {
        return rows(alertId).stream().filter(task -> task.getChannel() == channel).findFirst().orElseThrow();
    }

    private void toggle(Channel channel) {
        http().post().uri("/api/simulator/channels/{c}/toggle", channel).retrieve().toBodilessEntity();
    }
}
