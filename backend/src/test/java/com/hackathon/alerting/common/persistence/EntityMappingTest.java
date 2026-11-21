package com.hackathon.alerting.common.persistence;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.delivery.model.ChannelPreference;
import com.hackathon.alerting.delivery.model.OutboxStatus;
import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.model.WorkingHours;
import com.hackathon.alerting.ingest.model.Event;
import com.hackathon.alerting.ingest.model.EventStatus;
import com.hackathon.alerting.ingest.model.Source;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import com.hackathon.alerting.rules.model.GroupMode;
import com.hackathon.alerting.rules.model.Presentation;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.model.Severity;
import com.hackathon.alerting.runbooks.model.AuditAction;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.model.Runbook;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the JPA mappings against the real schema (Flyway V1 + V2 on PostgreSQL 16):
 * JSONB round-trips through Jackson 3, lowercase enum codes, lazy runbook content,
 * pooled sequences and the database invariants the entities rely on.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsAndReloadsTheWholeGraph() {
        Team team = new Team("Team Rates");
        team.setCondition(json("{\"path\":\"desk\",\"op\":\"==\",\"value\":\"rates\"}"));
        em.persist(team);

        User user = new User(team, "Anna Kowalska");
        user.setEmail("anna@bank.com");
        user.setTimezone(ZoneId.of("Europe/London"));
        user.setChannelPreferences(List.of(new ChannelPreference(Channel.SMS, "08-20"), ChannelPreference.anyTime(Channel.EMAIL)));
        user.setWorkingHours(new WorkingHours(LocalTime.of(8, 0), LocalTime.of(20, 0), List.of(1, 2, 3, 4, 5)));
        em.persist(user);

        Source source = new Source("payments-monitoring", SourceType.KAFKA, json("{\"topic\":\"workflow.events\"}"));
        em.persist(source);

        Runbook runbook = new Runbook(team, "restart-workflow.pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        em.persist(runbook);

        Rule rule = new Rule(team, "Rejected trades");
        rule.setAssignedSeverity(Severity.HIGH);
        rule.setChannels(List.of(Channel.EMAIL, Channel.UI));
        rule.setGroupByPaths(List.of("instrument"));
        rule.setGroupMode(GroupMode.PER_WINDOW);
        rule.setPresentation(new Presentation("Rejected {{trade.id}}"));
        rule.setOverrideRunbook(runbook);
        em.persist(rule);

        Alert alert = Alert.forRule(rule, fingerprint('a'), "Rejected T-1", json("{\"trade\":{\"id\":\"T-1\"}}"), NOW);
        alert.attachRunbook(runbook);
        em.persist(alert);

        em.persist(OutboxTask.toUser(alert, user, Channel.EMAIL, json("{\"subject\":\"Rejected T-1\"}")));
        em.persist(OutboxTask.toTeamFeed(alert, team, json("{\"title\":\"Rejected T-1\"}")));
        em.persist(new AuditLog(alert, AuditLog.SYSTEM_ACTOR, AuditAction.RUNBOOK_ATTACHED, json("{\"runbookId\":" + runbook.getId() + "}")));
        em.flush();
        em.clear();

        // Raw database representation is what other modules (native SQL, the UI) rely on
        assertThat(jdbc.queryForObject("select type from sources where id = ?", String.class, source.getId())).isEqualTo("kafka");
        assertThat(jdbc.queryForObject("select group_mode from rules where id = ?", String.class, rule.getId())).isEqualTo("per_window");
        assertThat(jsonColumn("select channels::text from rules where id = ?", rule.getId())).isEqualTo(json("[\"EMAIL\",\"UI\"]"));
        assertThat(jsonColumn("select presentation::text from rules where id = ?", rule.getId())).isEqualTo(json("{\"title\":\"Rejected {{trade.id}}\"}"));
        assertThat(jsonColumn("select channel_preferences::text from users where id = ?", user.getId()))
                .isEqualTo(json("[{\"channel\":\"SMS\",\"hours\":\"08-20\"},{\"channel\":\"EMAIL\"}]"));

        User loadedUser = em.find(User.class, user.getId());
        assertThat(loadedUser.getTimezone()).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(loadedUser.getChannelPreferences()).containsExactly(new ChannelPreference(Channel.SMS, "08-20"), ChannelPreference.anyTime(Channel.EMAIL));
        assertThat(loadedUser.getWorkingHours().from()).isEqualTo(LocalTime.of(8, 0));
        assertThat(loadedUser.getWorkingHours().days()).containsExactly(1, 2, 3, 4, 5);
        assertThat(loadedUser.getTeam().getId()).isEqualTo(team.getId());
        assertThat(loadedUser.getCreatedAt()).isNotNull();

        Rule loadedRule = em.find(Rule.class, rule.getId());
        assertThat(loadedRule.getChannels()).containsExactly(Channel.EMAIL, Channel.UI);
        assertThat(loadedRule.getGroupByPaths()).containsExactly("instrument");
        assertThat(loadedRule.getGroupMode()).isEqualTo(GroupMode.PER_WINDOW);
        assertThat(loadedRule.getPresentation().title()).isEqualTo("Rejected {{trade.id}}");
        assertThat(loadedRule.getAssignedSeverity()).isEqualTo(Severity.HIGH);
        assertThat(loadedRule.getOverrideRunbook().getId()).isEqualTo(runbook.getId());
        assertThat(loadedRule.getUpdatedAt()).isNotNull();

        Team loadedTeam = em.find(Team.class, team.getId());
        assertThat(loadedTeam.getCondition().get("value").asString()).isEqualTo("rates");

        Alert loadedAlert = em.find(Alert.class, alert.getId());
        assertThat(loadedAlert.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(loadedAlert.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(loadedAlert.getTeam().getId()).isEqualTo(team.getId());
        assertThat(loadedAlert.getSamplePayload().at("/trade/id").asString()).isEqualTo("T-1");
        assertThat(loadedAlert.getFirstSeenAt()).isEqualTo(NOW);

        Source loadedSource = em.find(Source.class, source.getId());
        assertThat(loadedSource.getType()).isEqualTo(SourceType.KAFKA);
        assertThat(loadedSource.getConfig().get("topic").asString()).isEqualTo("workflow.events");

        List<OutboxTask> outbox = em.getEntityManager()
                .createQuery("select o from OutboxTask o where o.alert.id = :id order by o.id", OutboxTask.class)
                .setParameter("id", alert.getId())
                .getResultList();
        assertThat(outbox).extracting(OutboxTask::getChannel).containsExactly(Channel.EMAIL, Channel.UI);
        assertThat(outbox).extracting(OutboxTask::getStatus).containsOnly(OutboxStatus.PENDING);
        assertThat(outbox.get(1).getUser()).isNull();
    }

    @Test
    void runbookContentIsLoadedLazily() {
        byte[] bytes = "# How to restart".getBytes(StandardCharsets.UTF_8);
        Runbook runbook = new Runbook(null, "restart.md", bytes);
        em.persist(runbook);
        em.flush();
        em.clear();

        Runbook loaded = em.find(Runbook.class, runbook.getId());
        assertThat(loaded.isGlobal()).isTrue();
        assertThat(loaded.getFileName()).isEqualTo("restart.md");
        assertThat(Hibernate.isPropertyInitialized(loaded, "content")).isFalse();

        assertThat(loaded.getContent()).isEqualTo(bytes);
        assertThat(Hibernate.isPropertyInitialized(loaded, "content")).isTrue();
    }

    @Test
    void eventsUsePersistAndKeepTheirAssignedId() {
        Alert unrouted = Alert.unrouted(fingerprint('b'), "Unrouted from legacy", json("{\"x\":1}"), NOW);
        em.persist(unrouted);
        UUID unroutedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        em.persist(Event.unrouted(unroutedId, "legacy", NOW, json("{\"x\":1}"), unrouted));
        em.persist(Event.failed(failedId, "legacy", NOW, json("{\"x\":2}"), "boom"));
        em.flush();
        em.clear();

        Event loaded = em.find(Event.class, unroutedId);
        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded.getStatus()).isEqualTo(EventStatus.UNROUTED);
        assertThat(loaded.getAlert().getId()).isEqualTo(unrouted.getId());
        assertThat(em.find(Event.class, failedId).getError()).isEqualTo("boom");
        assertThat(em.find(Alert.class, unrouted.getId()).isUnrouted()).isTrue();
    }

    @Test
    void pooledSequencesHandOutDistinctIds() {
        Fixture f = fixture();
        List<OutboxTask> tasks = java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> OutboxTask.toUser(f.alert, f.user, Channel.SMS, json("{\"text\":\"" + i + "\"}")))
                .toList();
        tasks.forEach(em::persist);
        em.flush();

        assertThat(tasks).extracting(OutboxTask::getId).doesNotHaveDuplicates().allMatch(id -> id > 0);
    }

    @Test
    void onlyOneOpenAlertPerFingerprint() {
        Fixture f = fixture();
        Alert duplicate = Alert.forRule(f.rule, f.alert.getFingerprint(), "duplicate", json("{}"), NOW);

        // IDENTITY ids are inserted on persist(), so the violation surfaces there rather than on flush()
        assertThatThrownBy(() -> {
            em.persist(duplicate);
            em.flush();
        }).hasMessageContaining("uq_alerts_open_fingerprint");
    }

    @Test
    void resolvedAlertFreesItsFingerprint() {
        Fixture f = fixture();
        f.alert.resolve(NOW);
        em.flush();

        em.persist(Alert.forRule(f.rule, f.alert.getFingerprint(), "next occurrence", json("{}"), NOW));
        em.flush();

        assertThat(jdbc.queryForObject("select count(*) from alerts where fingerprint = ?", Integer.class, f.alert.getFingerprint()))
                .isEqualTo(2);
    }

    @Test
    void deletingAnAlertCascadesToItsOutboxAndAuditButKeepsEvents() {
        Fixture f = fixture();
        em.persist(OutboxTask.toUser(f.alert, f.user, Channel.EMAIL, json("{}")));
        em.persist(new AuditLog(f.alert, "anna@bank.com", AuditAction.ACK, null));
        UUID eventId = UUID.randomUUID();
        em.persist(Event.failed(eventId, "legacy", NOW, json("{}"), "boom"));
        em.flush();
        jdbc.update("update events set alert_id = ? where event_id = ?", f.alert.getId(), eventId);

        jdbc.update("delete from alerts where id = ?", f.alert.getId());

        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where alert_id = ?", Integer.class, f.alert.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where alert_id = ?", Integer.class, f.alert.getId())).isZero();
        assertThat(jdbc.queryForObject("select alert_id from events where event_id = ?", Long.class, eventId)).isNull();
    }

    @Test
    void deletingARunbookDetachesItFromRulesAndAlerts() {
        Fixture f = fixture();
        Runbook runbook = new Runbook(f.team, "steps.txt", new byte[]{1});
        em.persist(runbook);
        f.rule.setOverrideRunbook(runbook);
        f.alert.attachRunbook(runbook);
        em.flush();

        jdbc.update("delete from runbooks where id = ?", runbook.getId());

        assertThat(jdbc.queryForObject("select override_runbook_id from rules where id = ?", Long.class, f.rule.getId())).isNull();
        assertThat(jdbc.queryForObject("select runbook_id from alerts where id = ?", Long.class, f.alert.getId())).isNull();
    }

    @Test
    void domainGuards() {
        Fixture f = fixture();
        assertThatThrownBy(() -> f.rule.setAssignedSeverity(Severity.UNKNOWN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.rule.setDedupWindowSec(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxTask.toUser(f.alert, f.user, Channel.UI, json("{}"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(f.alert.acknowledge()).isTrue();
        assertThat(f.alert.acknowledge()).isFalse();
        assertThat(f.alert.resolve(NOW)).isTrue();
        assertThat(f.alert.resolve(NOW)).isFalse();
        assertThatThrownBy(f.alert::acknowledge).isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- helpers

    private record Fixture(Team team, User user, Rule rule, Alert alert) {
    }

    private Fixture fixture() {
        Team team = new Team("Team " + UUID.randomUUID());
        em.persist(team);
        User user = new User(team, "Piotr");
        em.persist(user);
        Rule rule = new Rule(team, "rule");
        em.persist(rule);
        Alert alert = Alert.forRule(rule, fingerprint('f'), "title", json("{}"), NOW);
        em.persist(alert);
        em.flush();
        return new Fixture(team, user, rule, alert);
    }

    private JsonNode jsonColumn(String sql, Object id) {
        return json(jdbc.queryForObject(sql, String.class, id));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static String fingerprint(char c) {
        return String.valueOf(c).repeat(Alert.FINGERPRINT_LENGTH);
    }
}
