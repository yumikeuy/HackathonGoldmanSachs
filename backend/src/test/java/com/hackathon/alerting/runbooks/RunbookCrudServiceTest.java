package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.condition.DefaultConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.json.ExactPathFieldLocator;
import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.runbooks.api.RunbookCrudService;
import com.hackathon.alerting.runbooks.api.RunbookDto;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.RunbookFile;
import com.hackathon.alerting.support.JpaRepositoryTest;
import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JpaRepositoryTest
@RecordApplicationEvents
@Import({
        RunbookCrudService.class,
        DefaultConditionCompiler.class,
        ExactPathFieldLocator.class,
        RunbookCrudServiceTest.AfterCommitConfigChangedPublisher.class,
        RunbookCrudServiceTest.JsonConfig.class
})
class RunbookCrudServiceTest {

    /**
     * Stand-in for the Redis publisher: delivers {@link ConfigChangedEvent} locally, after the transaction commits —
     * the guarantee of the {@link ConfigChangedPublisher} contract this test checks — without needing Redis.
     */
    static class AfterCommitConfigChangedPublisher implements ConfigChangedPublisher {

        private final ApplicationEventPublisher events;

        AfterCommitConfigChangedPublisher(ApplicationEventPublisher events) {
            this.events = events;
        }

        @Override
        public void publish(String what) {
            ConfigChangedEvent event = new ConfigChangedEvent(what);
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                events.publishEvent(event);
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publishEvent(event);
                }
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired
    private RunbookCrudService service;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ApplicationEvents events;

    private TestData data;
    private Team teamA;
    private Team teamB;

    @BeforeEach
    void setUp() {
        data = new TestData(em);
        teamA = data.team();
        teamB = data.team();
    }

    @Test
    void createsListsUpdatesDownloadsAndDeletesRunbooks() {
        MockMultipartFile teamFile = new MockMultipartFile(
                "file", "dir/restart.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile globalFile = new MockMultipartFile(
                "file", "global.md", "text/markdown", "# Global".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile otherFile = new MockMultipartFile(
                "file", "other.txt", "text/plain", "other".getBytes(StandardCharsets.UTF_8));

        RunbookDto createdTeam = service.create(teamFile, teamA.getId(), "{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}");
        RunbookDto createdGlobal = service.create(globalFile, null, null);
        RunbookDto createdOther = service.create(otherFile, teamB.getId(), null);

        assertThat(createdTeam.fileName()).isEqualTo("restart.pdf");
        assertThat(createdTeam.fileUrl()).isEqualTo("/api/runbooks/" + createdTeam.id() + "/file");
        assertThat(createdTeam.teamId()).isEqualTo(teamA.getId());
        assertThat(createdTeam.condition().get("op").asString()).isEqualTo("==");
        assertThat(createdGlobal.teamId()).isNull();
        assertThat(createdGlobal.condition()).isNull();

        List<RunbookDto> teamAAndGlobal = service.list(teamA.getId());
        assertThat(teamAAndGlobal).extracting(RunbookDto::id)
                .containsExactly(createdTeam.id(), createdGlobal.id());
        assertThat(service.list(null)).extracting(RunbookDto::id)
                .contains(createdTeam.id(), createdGlobal.id(), createdOther.id());

        // Metadata-only update preserves file bytes
        RunbookDto metadataUpdated = service.update(
                createdTeam.id(), null, null, "{\"path\":\"desk\",\"op\":\"==\",\"value\":\"rates\"}");
        assertThat(metadataUpdated.teamId()).isNull();
        assertThat(metadataUpdated.fileName()).isEqualTo("restart.pdf");
        assertThat(metadataUpdated.condition().get("value").asString()).isEqualTo("rates");
        assertThat(service.downloadFile(createdTeam.id()).content())
                .isEqualTo("%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        // File replacement update
        MockMultipartFile replacement = new MockMultipartFile(
                "file", "restart-v2.yaml", "application/yaml", "steps: []".getBytes(StandardCharsets.UTF_8));
        RunbookDto fileUpdated = service.update(createdTeam.id(), replacement, teamA.getId(), null);
        assertThat(fileUpdated.fileName()).isEqualTo("restart-v2.yaml");
        assertThat(fileUpdated.teamId()).isEqualTo(teamA.getId());
        assertThat(fileUpdated.condition()).isNull();
        RunbookFile downloaded = service.downloadFile(createdTeam.id());
        assertThat(downloaded.fileName()).isEqualTo("restart-v2.yaml");
        assertThat(downloaded.content()).isEqualTo("steps: []".getBytes(StandardCharsets.UTF_8));

        // Deleting a runbook referenced by a rule and an alert succeeds (ON DELETE SET NULL)
        Rule rule = data.rule(teamA);
        Runbook ref = em.find(Runbook.class, createdTeam.id());
        rule.setOverrideRunbook(ref);
        Alert alert = data.alert(rule, Instant.now());
        alert.attachRunbook(ref);
        em.flush();
        em.clear();

        service.delete(createdTeam.id());
        em.flush();
        em.clear();

        assertThatThrownBy(() -> service.get(createdTeam.id())).isInstanceOf(NotFoundException.class);
        assertThat(em.find(Rule.class, rule.getId()).getOverrideRunbook()).isNull();
        assertThat(em.find(Alert.class, alert.getId()).getRunbook()).isNull();
    }

    @Test
    void validatesFileConditionTeamAndExistence() {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        MockMultipartFile valid = new MockMultipartFile("file", "ok.txt", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.create(empty, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> service.create(valid, null, "{not-json"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("invalid JSON");

        assertThatThrownBy(() -> service.create(valid, null, "{\"path\":\"a\",\"op\":\"equals\",\"value\":1}"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("condition:")
                .hasMessageContaining("'equals'");

        assertThatThrownBy(() -> service.create(valid, 999_999L, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Team 999999");

        assertThatThrownBy(() -> service.downloadFile(999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Runbook 999999");

        assertThatThrownBy(() -> service.delete(999_999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void stripsWindowsDirectorySeparatorsFromUploadedFileName() {
        MockMultipartFile windowsFile = new MockMultipartFile(
                "file", "C:\\Users\\ops\\win-restart.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8));

        RunbookDto created = service.create(windowsFile, null, null);

        assertThat(created.fileName()).isEqualTo("win-restart.pdf");
    }

    @Test
    void publishesRunbooksConfigChangedAfterCommit() {
        MockMultipartFile valid = new MockMultipartFile("file", "ok.txt", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
        RunbookDto created = service.create(valid, null, null);
        assertThat(events.stream(ConfigChangedEvent.class)).isEmpty();

        TestTransaction.flagForCommit();
        TestTransaction.end();

        assertThat(events.stream(ConfigChangedEvent.class))
                .extracting(ConfigChangedEvent::what)
                .containsExactly(ConfigKinds.RUNBOOKS);

        TestTransaction.start();
        service.delete(created.id());
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }
}
