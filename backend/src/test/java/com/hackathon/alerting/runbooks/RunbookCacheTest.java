package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.condition.DefaultConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.json.ExactPathFieldLocator;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.runbooks.cache.CompiledRunbook;
import com.hackathon.alerting.runbooks.cache.RunbookCache;
import com.hackathon.alerting.runbooks.cache.RunbookCatalog;
import com.hackathon.alerting.runbooks.repository.RunbookRepository;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunbookCacheTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TeamRepository teams = mock(TeamRepository.class);
    private final RunbookRepository runbooks = mock(RunbookRepository.class);
    private final RunbookCache cache = new RunbookCache(teams, runbooks,
            new DefaultConditionCompiler(new ExactPathFieldLocator()));

    @Test
    void compilesTeamRunbooksWithTeamStrictFlagAndGlobalAsFlexible() throws IOException {
        Team strictTeam = team(1L, "Team Strict", true);
        Team flexTeam = team(2L, "Team Flex", false);
        JsonNode notionalCond = json("{\"path\":\"trade.notional\",\"op\":\">\",\"value\":10000000}");

        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(strictTeam, flexTeam));
        when(runbooks.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(10L, 1L, "strict-rb.md", notionalCond),
                new RunbookSummary(11L, 2L, "flex-rb.md", notionalCond),
                new RunbookSummary(12L, null, "global-rb.yaml", notionalCond)
        ));
        cache.afterSingletonsInstantiated();

        JsonNode ratesEvent = loadFixture("events/rates-trade-rejected.json");
        JsonNode alienEvent = loadFixture("events/alien-schema.json");
        RunbookCatalog catalog = cache.current();

        assertThat(catalog.firstMatchingTeam(1L, ratesEvent)).isEmpty();
        assertThat(catalog.firstMatchingTeam(1L, alienEvent)).isEmpty();
        assertThat(catalog.firstMatchingTeam(2L, ratesEvent)).get().extracting(CompiledRunbook::id).isEqualTo(11L);
        assertThat(catalog.firstMatchingTeam(2L, alienEvent)).get().extracting(CompiledRunbook::id).isEqualTo(11L);
        assertThat(catalog.firstMatchingGlobal(ratesEvent)).get().extracting(CompiledRunbook::id).isEqualTo(12L);
        assertThat(catalog.firstMatchingGlobal(alienEvent)).get().extracting(CompiledRunbook::id).isEqualTo(12L);
    }

    @Test
    void excludesNullAndInvalidConditionsFromAutoMatchWhileKeepingByIdLookup() {
        Team team = team(1L, "Team Rates", false);
        JsonNode validCond = json("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}");
        JsonNode brokenCond = json("{\"path\":\"status\",\"op\":\"unknown_op\",\"value\":\"FAILED\"}");

        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(team));
        when(runbooks.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(1L, 1L, "override-only.pdf", null),
                new RunbookSummary(2L, null, "json-null.txt", json("null")),
                new RunbookSummary(3L, 1L, "broken.md", brokenCond),
                new RunbookSummary(4L, 1L, "valid-team.md", validCond),
                new RunbookSummary(5L, null, "valid-global.md", validCond)
        ));

        cache.afterSingletonsInstantiated();
        RunbookCatalog catalog = cache.current();

        assertThat(catalog.size()).isEqualTo(5);
        assertThat(catalog.findById(1L)).get().satisfies(rb -> {
            assertThat(rb.fileName()).isEqualTo("override-only.pdf");
            assertThat(rb.isAutoMatchable()).isFalse();
        });
        assertThat(catalog.findById(3L)).get().satisfies(rb -> {
            assertThat(rb.fileName()).isEqualTo("broken.md");
            assertThat(rb.isAutoMatchable()).isFalse();
        });

        assertThat(catalog.forTeam(1L)).extracting(CompiledRunbook::id).containsExactly(4L);
        assertThat(catalog.globalRunbooks()).extracting(CompiledRunbook::id).containsExactly(5L);
        assertThat(catalog.firstMatchingTeam(1L, json("{\"status\":\"FAILED\"}"))).get()
                .extracting(CompiledRunbook::id).isEqualTo(4L);
    }

    @Test
    void preservesAscendingIdOrderForTieBreaking() {
        Team team = team(1L, "Team Rates", false);
        JsonNode cond = json("{\"path\":\"type\",\"op\":\"==\",\"value\":\"ERR\"}");
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(team));
        when(runbooks.findAllSummaries()).thenReturn(List.of(
                new RunbookSummary(5L, 1L, "first.md", cond),
                new RunbookSummary(9L, 1L, "second.md", cond),
                new RunbookSummary(7L, null, "g1.yaml", cond),
                new RunbookSummary(15L, null, "g2.yaml", cond)
        ));

        cache.reload();
        JsonNode event = json("{\"type\":\"ERR\"}");

        assertThat(cache.current().firstMatchingTeam(1L, event)).get().extracting(CompiledRunbook::id).isEqualTo(5L);
        assertThat(cache.current().firstMatchingGlobal(event)).get().extracting(CompiledRunbook::id).isEqualTo(7L);
    }

    @Test
    void reloadsOnlyForRunbooksAndTeamsChanges() {
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of());
        when(runbooks.findAllSummaries()).thenReturn(List.of());

        cache.afterSingletonsInstantiated();
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.USERS));
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.RULES));
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.SOURCES));
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.RUNBOOKS));
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.TEAMS));

        verify(runbooks, times(3)).findAllSummaries(); // startup + RUNBOOKS + TEAMS
    }

    @Test
    void throwingConditionOrNullEventDoesNotFailEvaluation() {
        CompiledRunbook exploding = new CompiledRunbook(1L, 1L, "boom.md", event -> {
            throw new IllegalStateException("unexpected failure");
        });

        assertThat(exploding.matches(json("{}"))).isFalse();
        assertThat(exploding.matches(null)).isFalse();
    }

    private static Team team(long id, String name, boolean strict) {
        Team team = new Team(name);
        ReflectionTestUtils.setField(team, "id", id);
        team.setStrict(strict);
        return team;
    }

    private static JsonNode json(String raw) {
        return JSON.readTree(raw);
    }

    private static JsonNode loadFixture(String path) throws IOException {
        try (InputStream in = RunbookCacheTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return JSON.readTree(in);
        }
    }
}
