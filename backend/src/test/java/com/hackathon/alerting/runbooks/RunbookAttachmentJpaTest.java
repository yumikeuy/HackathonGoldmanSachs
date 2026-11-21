package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.condition.DefaultConditionCompiler;
import com.hackathon.alerting.common.json.ExactPathFieldLocator;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.runbooks.audit.DefaultAuditService;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.runbooks.matching.DefaultRunbookService;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.AuditLogRepository;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.support.JpaRepositoryTest;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.hackathon.alerting.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;

@JpaRepositoryTest
@Import({
        RunbookCache.class,
        DefaultRunbookService.class,
        DefaultAuditService.class,
        DefaultConditionCompiler.class,
        ExactPathFieldLocator.class
})
class RunbookAttachmentJpaTest {

    @Autowired
    private RunbookCache cache;

    @Autowired
    private DefaultRunbookService runbookService;

    @Autowired
    private RunbookRepository runbooks;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private TestEntityManager em;

    private TestData data;

    @BeforeEach
    void setUp() {
        data = new TestData(em);
    }

    @Test
    void attachesOverrideTeamAndGlobalRunbooksThroughHibernateProxiesAndWritesAudit() {
        Team team = data.team();

        Runbook overrideOnly = new Runbook(team, "override.pdf", "pdf".getBytes(StandardCharsets.UTF_8));
        overrideOnly = runbooks.save(overrideOnly);

        Runbook teamRunbook = new Runbook(team, "trade-rejection.md", "# trade".getBytes(StandardCharsets.UTF_8));
        teamRunbook.setCondition(json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"TRADE_REJECTED\"}"));
        teamRunbook = runbooks.save(teamRunbook);

        Runbook globalRunbook = new Runbook(null, "high-cpu.yaml", "metric: cpu".getBytes(StandardCharsets.UTF_8));
        globalRunbook.setCondition(json("{\"path\":\"metric\",\"op\":\"==\",\"value\":\"cpu\"}"));
        globalRunbook = runbooks.save(globalRunbook);

        Rule overrideRule = new Rule(team, "Override rule");
        overrideRule.setOverrideRunbook(overrideOnly);
        overrideRule = em.persist(overrideRule);
        Alert overrideAlert = data.alert(overrideRule, Instant.now());

        Rule normalRule = data.rule(team);
        Alert teamAlert = data.alert(normalRule, Instant.now());
        Alert globalAlert = data.alert(normalRule, Instant.now());

        em.flush();
        em.clear();
        cache.reload();

        Alert managedOverrideAlert = em.find(Alert.class, overrideAlert.getId());
        runbookService.attach(managedOverrideAlert, json("{\"type\":\"TRADE_REJECTED\",\"metric\":\"cpu\"}"));

        Alert managedTeamAlert = em.find(Alert.class, teamAlert.getId());
        runbookService.attach(managedTeamAlert, json("{\"type\":\"TRADE_REJECTED\",\"metric\":\"cpu\"}"));

        Alert managedGlobalAlert = em.find(Alert.class, globalAlert.getId());
        runbookService.attach(managedGlobalAlert, json("{\"metric\":\"cpu\"}"));

        em.flush();
        em.clear();

        assertThat(em.find(Alert.class, overrideAlert.getId()).getRunbook().getId()).isEqualTo(overrideOnly.getId());
        assertThat(em.find(Alert.class, teamAlert.getId()).getRunbook().getId()).isEqualTo(teamRunbook.getId());
        assertThat(em.find(Alert.class, globalAlert.getId()).getRunbook().getId()).isEqualTo(globalRunbook.getId());

        long overrideRunbookId = overrideOnly.getId();
        List<AuditLog> overrideAudit = auditLogs.findAllByAlertIdOrderByCreatedAtAscIdAsc(overrideAlert.getId());
        assertThat(overrideAudit).singleElement().satisfies(entry -> {
            assertThat(entry.getActor()).isEqualTo(AuditLog.SYSTEM_ACTOR);
            assertThat(entry.getActionType()).isEqualTo(AuditActions.RUNBOOK_ATTACHED);
            assertThat(entry.getDetails().get("runbookId").asLong()).isEqualTo(overrideRunbookId);
            assertThat(entry.getDetails().get("fileName").asString()).isEqualTo("override.pdf");
            assertThat(entry.getDetails().get("reason").asString()).isEqualTo("override");
        });
    }
}
