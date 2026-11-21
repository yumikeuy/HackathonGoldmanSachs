package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.condition.DefaultConditionCompiler;
import com.hackathon.alerting.common.json.ExactPathFieldLocator;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.runbooks.matching.DefaultRunbookService;
import com.hackathon.alerting.runbooks.model.AuditActions;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultRunbookServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TeamRepository teams = mock(TeamRepository.class);
    private final RunbookRepository runbookRepo = mock(RunbookRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RunbookCache cache = new RunbookCache(teams, runbookRepo,
            new DefaultConditionCompiler(new ExactPathFieldLocator()));
    private final DefaultRunbookService service = new DefaultRunbookService(cache, runbookRepo, audit);

    private final Team rates = team(1L, "Team Rates", false);
    private final Team pricing = team(2L, "Team Pricing", false);

    @BeforeEach
    void setUp() {
        when(runbookRepo.getReferenceById(anyLong())).thenAnswer(inv -> runbookRef(inv.getArgument(0), "stub"));
    }

    @Test
    void overrideRunbookBeatsMatchingTeamAndGlobalRunbooks() {
        Runbook override = runbookRef(100L, "override.pdf");
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(10L, 1L, "team.md", json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"TRADE_REJECTED\"}")),
                new RunbookSummary(20L, null, "global.yaml", json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"TRADE_REJECTED\"}")),
                new RunbookSummary(100L, 1L, "override.pdf", null)
        ));
        cache.reload();

        Rule rule = rule(5L, rates, override);
        Alert alert = alert(77L, rule);

        service.attach(alert, json("{\"type\":\"TRADE_REJECTED\"}"));

        assertThat(alert.getRunbook()).isNotNull();
        assertThat(alert.getRunbook().getId()).isEqualTo(100L);
        verifyAudit(77L, 100L, "override.pdf", "override");
    }

    @Test
    void overrideResolvesFileNameFromCacheWhenProxyIsUninitialized() {
        Runbook detachedProxy = mock(Runbook.class);
        when(detachedProxy.getId()).thenReturn(100L);
        when(detachedProxy.getFileName()).thenThrow(new LazyInitializationException("no session"));

        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(100L, 1L, "cached-override.pdf", null)
        ));
        cache.reload();

        Rule rule = rule(5L, rates, detachedProxy);
        Alert alert = alert(78L, rule);

        service.attach(alert, json("{}"));

        verify(detachedProxy, never()).getFileName();
        verifyAudit(78L, 100L, "cached-override.pdf", "override");
    }

    @Test
    void overrideFallsBackToSummaryQueryWhenCacheMissesUninitializedProxy() {
        Runbook staleProxy = mock(Runbook.class);
        when(staleProxy.getId()).thenReturn(200L);
        when(staleProxy.getFileName()).thenThrow(new LazyInitializationException("no session"));
        when(runbookRepo.findSummaryById(200L))
                .thenReturn(Optional.of(new RunbookSummary(200L, 1L, "db-fallback.pdf", null)));

        Rule rule = rule(5L, rates, staleProxy);
        Alert alert = alert(79L, rule);

        service.attach(alert, json("{}"));

        verify(staleProxy, never()).getFileName();
        verifyAudit(79L, 200L, "db-fallback.pdf", "override");
    }

    @Test
    void teamRunbookBeatsGlobalAndLowestIdWinsTie() {
        JsonNode matchAll = json("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}");
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(5L, 1L, "first-team.md", matchAll),
                new RunbookSummary(9L, 1L, "second-team.md", matchAll),
                new RunbookSummary(1L, null, "global-lower-id.yaml", matchAll)
        ));
        cache.reload();

        Alert alert = alert(80L, rule(6L, rates, null));
        service.attach(alert, json("{\"status\":\"FAILED\"}"));

        assertThat(alert.getRunbook().getId()).isEqualTo(5L);
        verifyAudit(80L, 5L, "first-team.md", "team");
    }

    @Test
    void globalRunbookAttachesWhenNoTeamRunbookMatchesUsingLowestId() {
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(10L, 1L, "team-other.md", json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"OTHER\"}")),
                new RunbookSummary(20L, null, "global-first.yaml", json("{\"path\":\"metric\",\"op\":\"in\",\"value\":[\"cpu\",\"memory\"]}")),
                new RunbookSummary(21L, null, "global-second.yaml", json("{\"path\":\"metric\",\"op\":\"==\",\"value\":\"cpu\"}"))
        ));
        cache.reload();

        Alert alert = alert(81L, rule(6L, rates, null));
        service.attach(alert, json("{\"metric\":\"cpu\"}"));

        assertThat(alert.getRunbook().getId()).isEqualTo(20L);
        verifyAudit(81L, 20L, "global-first.yaml", "global");
    }

    @Test
    void unroutedAlertCanMatchGlobalRunbookInFlexibleMode() throws IOException {
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of());
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(30L, null, "big-deal.md", json("{\"path\":\"deal.notional\",\"op\":\">\",\"value\":10000000}"))
        ));
        cache.reload();

        JsonNode alien = loadFixture("events/alien-schema.json");
        Alert unrouted = Alert.unrouted(fingerprint(), "Unrouted", alien, Instant.now());
        ReflectionTestUtils.setField(unrouted, "id", 82L);

        service.attach(unrouted, alien);

        assertThat(unrouted.getRunbook().getId()).isEqualTo(30L);
        verifyAudit(82L, 30L, "big-deal.md", "global");
    }

    @Test
    void nullConditionRunbookNeverAutoMatches() {
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(40L, 1L, "override-only-team.md", null),
                new RunbookSummary(41L, null, "override-only-global.md", null)
        ));
        cache.reload();

        Alert alert = alert(83L, rule(6L, rates, null));
        service.attach(alert, json("{\"type\":\"TRADE_REJECTED\"}"));

        assertThat(alert.getRunbook()).isNull();
        verifyNoInteractions(audit);
    }

    @Test
    void noMatchOrNullAlertIsNoOp() {
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(10L, 1L, "only-rates.md", json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"ONLY_THIS\"}"))
        ));
        cache.reload();

        Alert alert = alert(84L, rule(6L, rates, null));
        service.attach(alert, json("{\"type\":\"DIFFERENT\"}"));
        service.attach(null, json("{}"));

        assertThat(alert.getRunbook()).isNull();
        verifyNoInteractions(audit);
        verify(runbookRepo, never()).getReferenceById(anyLong());
    }

    @Test
    void sampleFixturesAttachExpectedRunbooks() throws IOException {
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates, pricing));
        when(runbookRepo.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(1L, 1L, "trade-rejection.md",
                        json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"TRADE_REJECTED\"}")),
                new RunbookSummary(2L, 2L, "pricing-workflow-restart.txt",
                        json("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}")),
                new RunbookSummary(3L, null, "high-cpu.yaml",
                        json("{\"path\":\"metric\",\"op\":\"in\",\"value\":[\"cpu\",\"memory\"]}"))
        ));
        cache.reload();

        Alert ratesAlert = alert(91L, rule(1L, rates, null));
        service.attach(ratesAlert, loadFixture("events/rates-trade-rejected.json"));
        assertThat(ratesAlert.getRunbook().getId()).isEqualTo(1L);
        verifyAudit(91L, 1L, "trade-rejection.md", "team");

        Alert pricingAlert = alert(92L, rule(2L, pricing, null));
        service.attach(pricingAlert, loadFixture("events/pricing-workflow-failed.json"));
        assertThat(pricingAlert.getRunbook().getId()).isEqualTo(2L);
        verifyAudit(92L, 2L, "pricing-workflow-restart.txt", "team");

        Alert infraAlert = alert(93L, rule(3L, rates, null));
        service.attach(infraAlert, loadFixture("events/infra-cpu-high.json"));
        assertThat(infraAlert.getRunbook().getId()).isEqualTo(3L);
        verifyAudit(93L, 3L, "high-cpu.yaml", "global");
    }

    private void verifyAudit(long alertId, long runbookId, String fileName, String reason) {
        ArgumentCaptor<JsonNode> detailsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(audit).log(eq(alertId), eq(AuditLog.SYSTEM_ACTOR), eq(AuditActions.RUNBOOK_ATTACHED), detailsCaptor.capture());
        JsonNode details = detailsCaptor.getValue();
        assertThat(details.get("runbookId").asLong()).isEqualTo(runbookId);
        assertThat(details.get("fileName").asString()).isEqualTo(fileName);
        assertThat(details.get("reason").asString()).isEqualTo(reason);
    }

    private static Team team(long id, String name, boolean strict) {
        Team team = new Team(name);
        ReflectionTestUtils.setField(team, "id", id);
        team.setStrict(strict);
        return team;
    }

    private static Runbook runbookRef(long id, String fileName) {
        Runbook rb = new Runbook(null, fileName, new byte[]{1});
        ReflectionTestUtils.setField(rb, "id", id);
        return rb;
    }

    private static Rule rule(long id, Team team, Runbook override) {
        Rule rule = new Rule(team, "rule-" + id);
        ReflectionTestUtils.setField(rule, "id", id);
        rule.setOverrideRunbook(override);
        return rule;
    }

    private static Alert alert(long id, Rule rule) {
        Alert alert = Alert.forRule(rule, fingerprint(), "Alert " + id, json("{}"), Instant.now());
        ReflectionTestUtils.setField(alert, "id", id);
        return alert;
    }

    private static String fingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static JsonNode json(String raw) {
        return JSON.readTree(raw);
    }

    private static JsonNode loadFixture(String path) throws IOException {
        try (InputStream in = DefaultRunbookServiceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return JSON.readTree(in);
        }
    }
}
