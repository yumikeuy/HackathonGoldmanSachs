package com.hackathon.alerting.rules.cache;

import com.hackathon.alerting.common.condition.DefaultConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedEvent;
import com.hackathon.alerting.common.config.ConfigKinds;
import com.hackathon.alerting.common.json.ExactPathFieldLocator;
import com.hackathon.alerting.delivery.model.Team;
import com.hackathon.alerting.delivery.repository.TeamRepository;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.repository.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hackathon.alerting.rules.RulesTestData.json;
import static com.hackathon.alerting.rules.RulesTestData.rule;
import static com.hackathon.alerting.rules.RulesTestData.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingCacheTest {

    private final TeamRepository teams = mock(TeamRepository.class);
    private final RuleRepository rules = mock(RuleRepository.class);
    private final RoutingCache cache = new RoutingCache(teams, rules,
            new DefaultConditionCompiler(new ExactPathFieldLocator()));

    private final Team rates = team(1, "Team Rates", "{\"path\":\"desk\",\"op\":\"==\",\"value\":\"rates\"}");
    private final Team fx = team(2, "Team FX", "{\"path\":\"ccy\",\"op\":\"==\",\"value\":\"EUR\"}");
    private final Team noCondition = team(3, "Team Idle", null);

    @BeforeEach
    void setUp() {
        Rule failed = rule(10, rates, "{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}");
        failed.setPriority(5);
        Rule catchAll = rule(11, rates, null);
        Rule broken = rule(12, rates, "{\"path\":\"x\",\"op\":\"no-such-op\",\"value\":1}");
        Rule fxRule = rule(20, fx, null);
        fxRule.setResolveCondition(json("{\"path\":\"status\",\"op\":\"==\",\"value\":\"OK\"}"));
        when(teams.findAllByOrderByIdAsc()).thenReturn(List.of(rates, fx, noCondition));
        when(rules.findAllWithTeamByActiveTrueOrderByPriorityAscIdAsc()).thenReturn(List.of(failed, catchAll, broken, fxRule));
        cache.afterSingletonsInstantiated();
    }

    @Test
    void routesToEveryMatchingTeamAndNeverToTeamsWithoutCondition() {
        RoutingTable table = cache.current();

        assertThat(table.teamsMatching(json("{\"desk\":\"rates\",\"ccy\":\"EUR\"}")))
                .extracting(TeamRoute::teamId).containsExactly(1L, 2L);
        assertThat(table.teamsMatching(json("{\"desk\":\"equities\"}"))).isEmpty();
    }

    @Test
    void keepsRulesInPriorityOrderAndSkipsRulesThatDoNotCompile() {
        TeamRoute ratesRoute = cache.current().teams().getFirst();

        assertThat(ratesRoute.rules()).extracting(RuleRoute::id).containsExactly(10L, 11L);
        assertThat(cache.current().ruleCount()).isEqualTo(3);
        assertThat(ratesRoute.rules().get(0).fires(json("{\"status\":\"FAILED\"}"))).isTrue();
        assertThat(ratesRoute.rules().get(0).fires(json("{\"status\":\"OK\"}"))).isFalse();
        assertThat(ratesRoute.rules().get(1).fires(json("{}"))).as("null rule condition matches all").isTrue();
    }

    @Test
    void compilesResolveConditions() {
        RuleRoute fxRule = cache.current().teams().get(1).rules().getFirst();

        assertThat(fxRule.resolves(json("{\"status\":\"OK\"}"))).isTrue();
        assertThat(fxRule.resolves(json("{\"status\":\"FAILED\"}"))).isFalse();
        assertThat(cache.current().teams().getFirst().rules().getFirst().resolves(json("{\"status\":\"OK\"}")))
                .as("no resolve condition never resolves").isFalse();
    }

    @Test
    void reloadsOnlyForTeamAndRuleChanges() {
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.USERS));
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.RULES));
        cache.onConfigChanged(new ConfigChangedEvent(ConfigKinds.TEAMS));

        verify(teams, times(3)).findAllByOrderByIdAsc();   // initial load + RULES + TEAMS
    }

    @Test
    void aThrowingPredicateCountsAsNoMatch() {
        TeamRoute exploding = new TeamRoute(9, "bad", false, event -> {
            throw new IllegalStateException("bug in a condition");
        }, List.of());

        assertThat(new RoutingTable(List.of(exploding)).teamsMatching(json("{}"))).isEmpty();
    }
}
