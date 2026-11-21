package com.hackathon.alerting.rules.dedup;

import com.hackathon.alerting.common.json.DefaultJsonFieldLocator;
import com.hackathon.alerting.rules.cache.RuleRoute;
import com.hackathon.alerting.rules.model.GroupMode;
import com.hackathon.alerting.rules.model.Rule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.hackathon.alerting.rules.RulesTestData.json;
import static com.hackathon.alerting.rules.RulesTestData.rule;
import static com.hackathon.alerting.rules.RulesTestData.team;
import static org.assertj.core.api.Assertions.assertThat;

class FingerprintCalculatorTest {

    private static final Instant T = Instant.parse("2026-11-21T10:00:10Z");

    private final FingerprintCalculator calculator = new FingerprintCalculator(new DefaultJsonFieldLocator());

    @Test
    void groupsByRuleAndGroupByValues() {
        RuleRoute route = route(7, List.of("instrument"), GroupMode.WHILE_OPEN);

        String eur = calculator.forRule(route, json("{\"instrument\":\"EUR\",\"other\":1}"), T);

        assertThat(eur).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(calculator.forRule(route, json("{\"instrument\":\"EUR\",\"other\":2}"), T.plusSeconds(3600))).isEqualTo(eur);
        assertThat(calculator.forRule(route, json("{\"instrument\":\"USD\"}"), T)).isNotEqualTo(eur);
        assertThat(calculator.forRule(route(8, List.of("instrument"), GroupMode.WHILE_OPEN), json("{\"instrument\":\"EUR\"}"), T))
                .isNotEqualTo(eur);
    }

    @Test
    void distinguishesMissingNullStringAndNumberValues() {
        RuleRoute route = route(7, List.of("k"), GroupMode.WHILE_OPEN);

        List<String> fingerprints = List.of(
                calculator.forRule(route, json("{}"), T),
                calculator.forRule(route, json("{\"k\":\"\"}"), T),
                calculator.forRule(route, json("{\"k\":\"1\"}"), T),
                calculator.forRule(route, json("{\"k\":1}"), T));

        assertThat(fingerprints).doesNotHaveDuplicates();
    }

    @Test
    void perWindowStartsANewGroupEveryWindow() {
        RuleRoute route = route(7, List.of(), GroupMode.PER_WINDOW);   // window 300 s; T is 10 s into a window

        String first = calculator.forRule(route, json("{}"), T);

        assertThat(calculator.forRule(route, json("{}"), T.plusSeconds(289))).isEqualTo(first);
        assertThat(calculator.forRule(route, json("{}"), T.plusSeconds(290))).isNotEqualTo(first);
    }

    @Test
    void unroutedGroupsBySourceAndTopLevelKeysInAnyOrder() {
        String fp = calculator.forUnrouted("legacy", json("{\"a\":1,\"b\":{\"x\":1}}"));

        assertThat(calculator.forUnrouted("legacy", json("{\"b\":2,\"a\":\"other\"}"))).isEqualTo(fp);
        assertThat(calculator.forUnrouted("legacy", json("{\"a\":1}"))).isNotEqualTo(fp);
        assertThat(calculator.forUnrouted("other-source", json("{\"a\":1,\"b\":2}"))).isNotEqualTo(fp);
    }

    private static RuleRoute route(long id, List<String> groupBy, GroupMode mode) {
        Rule rule = rule(id, team(1, "t", null), null);
        rule.setGroupByPaths(groupBy);
        rule.setGroupMode(mode);
        return new RuleRoute(rule, false, event -> true, null);
    }
}
