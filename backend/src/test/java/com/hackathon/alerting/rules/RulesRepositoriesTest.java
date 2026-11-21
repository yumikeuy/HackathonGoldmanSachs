package com.hackathon.alerting.rules;

import com.hackathon.alerting.delivery.model.OutboxTask;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.model.Severity;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.rules.repository.OccurrenceDelta;
import com.hackathon.alerting.rules.repository.RuleRepository;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.support.JpaRepositoryTest;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.hackathon.alerting.support.TestData.json;
import static com.hackathon.alerting.support.TestData.randomFingerprint;
import static org.assertj.core.api.Assertions.assertThat;

@JpaRepositoryTest
class RulesRepositoriesTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private RuleRepository rules;

    @Autowired
    private AlertRepository alerts;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private Team team;
    private Rule rule;

    @BeforeEach
    void setUp() {
        data = new TestData(em);
        team = data.team();
        rule = data.rule(team);
    }

    @Test
    void listsRulesInPriorityOrder() {
        Rule urgent = new Rule(team, "urgent");
        urgent.setPriority(1);
        em.persist(urgent);
        Rule off = new Rule(team, "off");
        off.setActive(false);
        em.persist(off);

        assertThat(rules.findAllByTeamIdOrderByPriorityAscIdAsc(team.getId())).containsExactly(urgent, rule, off);
        assertThat(rules.findAllByActiveTrueOrderByPriorityAscIdAsc()).contains(urgent, rule).doesNotContain(off);
    }

    @Test
    void insertsOnlyOneOpenAlertPerFingerprint() {
        String fp = randomFingerprint();
        rule.setAssignedSeverity(Severity.CRITICAL);

        Optional<Long> created = alerts.insertIfNoOpenAlert(Alert.forRule(rule, fp, "first", json("{\"n\":1}"), NOW));
        Optional<Long> duplicate = alerts.insertIfNoOpenAlert(Alert.forRule(rule, fp, "second", json("{\"n\":2}"), NOW));

        assertThat(created).isPresent();
        assertThat(duplicate).isEmpty();
        Alert stored = alerts.findOpenByFingerprint(fp).orElseThrow();
        assertThat(stored.getId()).isEqualTo(created.get());
        assertThat(stored.getTitle()).isEqualTo("first");
        assertThat(stored.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(stored.getTeam()).isEqualTo(team);
        assertThat(stored.getSamplePayload()).isEqualTo(json("{\"n\":1}"));
        assertThat(stored.getFirstSeenAt()).isEqualTo(NOW);
    }

    @Test
    void resolvedAlertNoLongerBlocksTheFingerprint() {
        String fp = randomFingerprint();
        long firstId = alerts.insertIfNoOpenAlert(Alert.forRule(rule, fp, "t", json("{}"), NOW)).orElseThrow();
        alerts.findById(firstId).orElseThrow().resolve(NOW);
        alerts.flush();

        Optional<Long> next = alerts.insertIfNoOpenAlert(Alert.forRule(rule, fp, "t", json("{}"), NOW));

        assertThat(next).isPresent().isNotEqualTo(Optional.of(firstId));
        assertThat(alerts.findOpenByFingerprint(fp).orElseThrow().getId()).isEqualTo(next.get());
    }

    @Test
    void insertsUnroutedAlertWithoutRuleAndTeam() {
        String fp = randomFingerprint();

        long id = alerts.insertIfNoOpenAlert(Alert.unrouted(fp, "Unrouted", json("{\"x\":1}"), NOW)).orElseThrow();

        Alert stored = alerts.findById(id).orElseThrow();
        assertThat(stored.isUnrouted()).isTrue();
        assertThat(stored.getTeam()).isNull();
        assertThat(stored.getSeverity()).isEqualTo(Severity.UNKNOWN);
    }

    @Test
    void addsOccurrencesInBatchAndKeepsTheLatestLastSeen() {
        long a = alerts.insertIfNoOpenAlert(Alert.forRule(rule, randomFingerprint(), "a", json("{}"), NOW)).orElseThrow();
        long b = alerts.insertIfNoOpenAlert(Alert.forRule(rule, randomFingerprint(), "b", json("{}"), NOW)).orElseThrow();

        int updated = alerts.addOccurrences(List.of(
                new OccurrenceDelta(a, 499, NOW.plusSeconds(30)),
                new OccurrenceDelta(b, 2, NOW.minusSeconds(30)),   // older than stored: last_seen_at must not go back
                new OccurrenceDelta(Long.MAX_VALUE, 1, NOW)));     // purged alert: ignored

        assertThat(updated).isEqualTo(2);
        em.clear();
        Alert alertA = alerts.findById(a).orElseThrow();
        Alert alertB = alerts.findById(b).orElseThrow();
        assertThat(alertA.getOccurrencesCount()).isEqualTo(500);
        assertThat(alertA.getLastSeenAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(alertB.getOccurrencesCount()).isEqualTo(3);
        assertThat(alertB.getLastSeenAt()).isEqualTo(NOW);
        assertThat(alerts.addOccurrences(List.of())).isZero();
    }

    @Test
    void pagesTheTeamFeedByLastSeenWithStableTieBreak() {
        List<Alert> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(data.alert(rule, NOW.minusSeconds(i / 2)));   // pairs share last_seen_at
        }
        Alert acked = expected.get(4);
        acked.acknowledge();
        em.flush();

        List<Alert> seen = new ArrayList<>();
        ScrollPosition position = ScrollPosition.keyset();
        Window<Alert> page;
        do {
            page = alerts.findByTeamIdOrderByLastSeenAtDescIdAsc(team.getId(), position, Limit.of(2));
            seen.addAll(page.getContent());
            if (!page.isEmpty()) {
                position = page.positionAt(page.size() - 1);
            }
        } while (page.hasNext());

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(alerts.findByTeamIdAndStatusInOrderByLastSeenAtDescIdAsc(
                team.getId(), Set.of(AlertStatus.ACKED), ScrollPosition.keyset(), Limit.of(50)).getContent())
                .containsExactly(acked);
    }

    @Test
    void pagesUnroutedAlertsSeparately() {
        Alert unrouted = em.persist(Alert.unrouted(randomFingerprint(), "u", json("{}"), NOW.plus(1, ChronoUnit.DAYS)));
        data.alert(rule, NOW.plus(2, ChronoUnit.DAYS));

        Window<Alert> page = alerts.findByTeamIsNullOrderByLastSeenAtDescIdAsc(ScrollPosition.keyset(), Limit.of(1));

        assertThat(page.getContent()).containsExactly(unrouted);
    }

    @Test
    void cleanupDeletesOldResolvedAlertsWithTheirOutboxAndAudit() {
        Alert old = data.alert(rule, NOW.minus(10, ChronoUnit.DAYS));
        old.resolve(NOW.minus(8, ChronoUnit.DAYS));
        Alert recent = data.alert(rule, NOW);
        recent.resolve(NOW);
        Alert open = data.alert(rule, NOW.minus(30, ChronoUnit.DAYS));
        em.persist(OutboxTask.toTeamFeed(old, team, json("{}")));
        em.persist(new AuditLog(old, AuditLog.SYSTEM_ACTOR, AuditActions.AUTO_CLOSE, null));
        em.flush();

        int deleted = alerts.deleteResolvedBefore(NOW.minus(7, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from alerts where id in (?, ?, ?)", Integer.class,
                old.getId(), recent.getId(), open.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from delivery_outbox where alert_id = ?", Integer.class, old.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where alert_id = ?", Integer.class, old.getId())).isZero();
    }
}
