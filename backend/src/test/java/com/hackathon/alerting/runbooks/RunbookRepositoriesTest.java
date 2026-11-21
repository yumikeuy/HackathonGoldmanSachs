package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.AuditLogRepository;
import com.hackathon.alerting.runbooks.repository.RunbookFile;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import com.hackathon.alerting.support.JpaRepositoryTest;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.hackathon.alerting.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;

@JpaRepositoryTest
class RunbookRepositoriesTest {

    @Autowired
    private RunbookRepository runbooks;

    @Autowired
    private AuditLogRepository audit;

    @Autowired
    private TestEntityManager em;

    private TestData data;
    private Team team;

    @BeforeEach
    void setUp() {
        data = new TestData(em);
        team = data.team();
    }

    @Test
    void summariesCarryMetadataButNoBytes() {
        Runbook teamRunbook = new Runbook(team, "restart.pdf", new byte[]{1, 2, 3});
        teamRunbook.setCondition(json("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}"));
        runbooks.save(teamRunbook);
        Runbook global = runbooks.save(new Runbook(null, "generic.md", new byte[]{4}));
        runbooks.flush();
        em.clear();

        assertThat(runbooks.findSummariesByTeamId(team.getId()))
                .containsExactly(new RunbookSummary(teamRunbook.getId(), team.getId(), "restart.pdf", teamRunbook.getCondition()));
        assertThat(runbooks.findGlobalSummaries()).extracting(RunbookSummary::id).contains(global.getId());
        assertThat(runbooks.findGlobalSummaries()).allMatch(RunbookSummary::isGlobal);
        assertThat(runbooks.findAllSummaries()).extracting(RunbookSummary::id).contains(teamRunbook.getId(), global.getId());
        assertThat(runbooks.findSummaryById(global.getId())).hasValueSatisfying(s -> {
            assertThat(s.fileName()).isEqualTo("generic.md");
            assertThat(s.condition()).isNull();
        });
    }

    @Test
    void servesTheFileByItsOwnQuery() {
        byte[] bytes = "steps".getBytes(StandardCharsets.UTF_8);
        Runbook runbook = runbooks.saveAndFlush(new Runbook(team, "steps.txt", bytes));
        em.clear();

        RunbookFile file = runbooks.findFileById(runbook.getId()).orElseThrow();

        assertThat(file.fileName()).isEqualTo("steps.txt");
        assertThat(file.content()).isEqualTo(bytes);
        assertThat(runbooks.findFileById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void returnsAnAlertsAuditTrailInOrder() {
        Alert alert = data.alert(data.rule(team), Instant.now());
        AuditLog attached = audit.save(new AuditLog(alert, AuditLog.SYSTEM_ACTOR, AuditActions.RUNBOOK_ATTACHED, null));
        AuditLog acked = audit.save(new AuditLog(alert, "anna@bank.com", AuditActions.ACK, null));
        AuditLog custom = audit.save(new AuditLog(alert, AuditLog.ruleActor(7), "CUSTOM_ACTION", json("{\"k\":1}")));
        audit.flush();

        assertThat(audit.findAllByAlertIdOrderByCreatedAtAscIdAsc(alert.getId())).containsExactly(attached, acked, custom);
    }
}
