package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.runbooks.audit.AuditEntry;
import com.hackathon.alerting.runbooks.audit.AuditQueryService;
import com.hackathon.alerting.runbooks.audit.DefaultAuditService;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.repository.AuditLogRepository;
import com.hackathon.alerting.support.JpaRepositoryTest;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static com.hackathon.alerting.support.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JpaRepositoryTest
@Import({DefaultAuditService.class, AuditQueryService.class})
class DefaultAuditServiceTest {

    @Autowired
    private DefaultAuditService auditService;

    @Autowired
    private AuditQueryService auditQueries;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private TestEntityManager em;

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
    void logsAuditRecordsForAlertAndReturnsOldestFirst() {
        Alert alert = data.alert(rule, Instant.now());
        Alert otherAlert = data.alert(rule, Instant.now());
        em.flush();

        auditService.log(alert.getId(), AuditLog.SYSTEM_ACTOR, AuditActions.RUNBOOK_ATTACHED, json("{\"runbookId\":5}"));
        auditService.log(alert.getId(), "anna@bank.com", AuditActions.ACK, null);
        auditService.log(alert.getId(), AuditLog.ruleActor(rule.getId()), "CUSTOM_EVENT", json("{\"note\":\"ok\"}"));
        auditService.log(otherAlert.getId(), "piotr@bank.com", AuditActions.RESOLVE, null);
        em.flush();
        em.clear();

        List<AuditEntry> trail = auditQueries.findByAlertId(alert.getId());

        assertThat(trail).hasSize(3);
        assertThat(trail).extracting(AuditEntry::alertId).containsOnly(alert.getId());
        assertThat(trail).extracting(AuditEntry::actor)
                .containsExactly(AuditLog.SYSTEM_ACTOR, "anna@bank.com", AuditLog.ruleActor(rule.getId()));
        assertThat(trail).extracting(AuditEntry::actionType)
                .containsExactly(AuditActions.RUNBOOK_ATTACHED, AuditActions.ACK, "CUSTOM_EVENT");
        assertThat(trail.get(0).details()).isEqualTo(json("{\"runbookId\":5}"));
        assertThat(trail.get(1).details()).isEqualTo(json("{}"));
        assertThat(trail.get(2).createdAt()).isNotNull();
    }

    @Test
    void logsSystemRecordWithoutAlert() {
        auditService.log(null, AuditLog.SYSTEM_ACTOR, "CONFIG_RELOAD", json("{\"kind\":\"runbooks\"}"));
        em.flush();
        em.clear();

        List<AuditLog> all = auditLogs.findAll();
        assertThat(all).singleElement().satisfies(entry -> {
            assertThat(entry.getAlert()).isNull();
            assertThat(entry.getActor()).isEqualTo(AuditLog.SYSTEM_ACTOR);
            assertThat(entry.getActionType()).isEqualTo("CONFIG_RELOAD");
            assertThat(entry.getDetails()).isEqualTo(json("{\"kind\":\"runbooks\"}"));
        });
    }

    @Test
    void returnsEmptyListForExistingAlertWithoutHistoryAndThrowsNotFoundForUnknownAlert() {
        Alert alert = data.alert(rule, Instant.now());
        em.flush();
        em.clear();

        assertThat(auditQueries.findByAlertId(alert.getId())).isEmpty();
        assertThatThrownBy(() -> auditQueries.findByAlertId(999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Alert 999999 not found");
    }
}
