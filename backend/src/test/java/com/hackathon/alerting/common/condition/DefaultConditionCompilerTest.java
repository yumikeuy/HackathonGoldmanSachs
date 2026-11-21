package com.hackathon.alerting.common.condition;

import com.hackathon.alerting.common.json.DefaultJsonFieldLocator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for {@link DefaultConditionCompiler} covering all operators and strict/flexible modes. */
class DefaultConditionCompilerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConditionCompiler compiler = new DefaultConditionCompiler(new DefaultJsonFieldLocator());

    @Test
    void nullConditionMatchesEverything() {
        assertThat(compiler.compile(null, false).test(json("{}"))).isTrue();
        assertThat(compiler.compile(json("null"), false).test(json("{}"))).isTrue();
    }

    @Test
    void evaluatesAndOrAndEquality() {
        Predicate<JsonNode> predicate = compiler.compile(json("""
                {"and": [
                  {"or": [{"path":"metric","op":"==","value":"cpu"}, {"path":"metric","op":"==","value":"memory"}]},
                  {"path":"host.env","op":"==","value":"prod"}
                ]}"""), false);

        assertThat(predicate.test(json("{\"metric\":\"cpu\",\"host\":{\"env\":\"prod\"}}"))).isTrue();
        assertThat(predicate.test(json("{\"metric\":\"disk\",\"host\":{\"env\":\"prod\"}}"))).isFalse();
        assertThat(predicate.test(json("{\"metric\":\"memory\"}"))).isFalse();
    }

    @Test
    void evaluatesAllTwelveOperators() {
        JsonNode event = json("""
                {
                  "status": "FAILED",
                  "code": 503,
                  "ratio": 10.0,
                  "region": "eu-west-1",
                  "error": "Connection timeout after 5s",
                  "currency": "EUR-IRS",
                  "host": "prod-db-03",
                  "nullable": null
                }
                """);

        assertThat(compiler.compile(json("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"status\",\"op\":\"!=\",\"value\":\"OK\"}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"status\",\"op\":\"!=\",\"value\":\"FAILED\"}"), true).test(event)).isFalse();

        assertThat(compiler.compile(json("{\"path\":\"ratio\",\"op\":\"==\",\"value\":10}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"code\",\"op\":\">\",\"value\":500}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"code\",\"op\":\">=\",\"value\":503}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"code\",\"op\":\"<\",\"value\":504}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"code\",\"op\":\"<=\",\"value\":503}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"code\",\"op\":\">\",\"value\":503}"), true).test(event)).isFalse();

        assertThat(compiler.compile(json("{\"path\":\"region\",\"op\":\"in\",\"value\":[\"us-east-1\",\"eu-west-1\"]}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"region\",\"op\":\"not_in\",\"value\":[\"us-east-1\",\"ap-south-1\"]}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"region\",\"op\":\"not_in\",\"value\":[\"eu-west-1\"]}"), true).test(event)).isFalse();

        assertThat(compiler.compile(json("{\"path\":\"error\",\"op\":\"contains\",\"value\":\"timeout\"}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"error\",\"op\":\"contains\",\"value\":\"refused\"}"), true).test(event)).isFalse();

        assertThat(compiler.compile(json("{\"path\":\"currency\",\"op\":\"regex\",\"value\":\"^(EUR|USD)\"}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"currency\",\"op\":\"regex\",\"value\":\"^GBP\"}"), true).test(event)).isFalse();

        assertThat(compiler.compile(json("{\"path\":\"host\",\"op\":\"glob\",\"value\":\"prod-??-*\"}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"host\",\"op\":\"glob\",\"value\":\"staging-*\"}"), true).test(event)).isFalse();

        assertThat(compiler.compile(json("{\"path\":\"status\",\"op\":\"exists\",\"value\":true}"), true).test(event)).isTrue();
        assertThat(compiler.compile(json("{\"path\":\"nullable\",\"op\":\"exists\",\"value\":true}"), true).test(event)).isFalse();
        assertThat(compiler.compile(json("{\"path\":\"missing\",\"op\":\"exists\",\"value\":false}"), true).test(event)).isTrue();
    }

    @Test
    void missingFieldReturnsFalseForEveryComparisonIncludingNegations() {
        JsonNode event = json("{\"other\": 1}");

        for (String conditionJson : new String[]{
                "{\"path\":\"missing\",\"op\":\"==\",\"value\":\"x\"}",
                "{\"path\":\"missing\",\"op\":\"!=\",\"value\":\"x\"}",
                "{\"path\":\"missing\",\"op\":\">\",\"value\":0}",
                "{\"path\":\"missing\",\"op\":\"<=\",\"value\":100}",
                "{\"path\":\"missing\",\"op\":\"in\",\"value\":[\"a\"]}",
                "{\"path\":\"missing\",\"op\":\"not_in\",\"value\":[\"a\"]}",
                "{\"path\":\"missing\",\"op\":\"contains\",\"value\":\"a\"}",
                "{\"path\":\"missing\",\"op\":\"regex\",\"value\":\".*\"}",
                "{\"path\":\"missing\",\"op\":\"glob\",\"value\":\"*\"}",
                "{\"path\":\"missing\",\"op\":\"exists\",\"value\":true}"
        }) {
            assertThat(compiler.compile(json(conditionJson), false).test(event))
                    .as(conditionJson)
                    .isFalse();
        }
    }

    @Test
    void flexibleModeCoercesNumericStringsAndFindsNestedKeysWhileStrictRejectsThem() throws IOException {
        JsonNode rates = loadFixture("events/rates-trade-rejected.json");
        JsonNode alien = loadFixture("events/alien-schema.json");
        JsonNode pricing = loadFixture("events/pricing-workflow-failed.json");
        JsonNode infra = loadFixture("events/infra-cpu-high.json");

        JsonNode notionalGt10M = json("{\"path\":\"trade.notional\",\"op\":\">\",\"value\":10000000}");
        assertThat(compiler.compile(notionalGt10M, false).test(rates)).isTrue();
        assertThat(compiler.compile(notionalGt10M, true).test(rates)).isFalse();

        JsonNode notionalEq = json("{\"path\":\"trade.notional\",\"op\":\"==\",\"value\":15000000}");
        assertThat(compiler.compile(notionalEq, false).test(rates)).isTrue();
        assertThat(compiler.compile(notionalEq, true).test(rates)).isFalse();

        assertThat(compiler.compile(notionalGt10M, false).test(alien)).isTrue();
        assertThat(compiler.compile(notionalGt10M, true).test(alien)).isFalse();

        JsonNode pricingCondition = json("""
                {"and": [
                  {"path":"team","op":"==","value":"pricing"},
                  {"path":"status","op":"==","value":"FAILED"},
                  {"path":"error","op":"contains","value":"Timeout"}
                ]}""");
        assertThat(compiler.compile(pricingCondition, true).test(pricing)).isTrue();

        JsonNode infraCondition = json("""
                {"and": [
                  {"or": [
                    {"path":"metric","op":"==","value":"cpu"},
                    {"path":"metric","op":"==","value":"memory"}
                  ]},
                  {"path":"value","op":">","value":90},
                  {"path":"host","op":"glob","value":"prod-*"}
                ]}""");
        assertThat(compiler.compile(infraCondition, true).test(infra)).isTrue();
    }

    @Test
    void globEscapesRegexMetacharactersLiterally() {
        JsonNode event = json("{\"host\":\"svc.prod(1).internal\"}");

        assertThat(compiler.compile(json("{\"path\":\"host\",\"op\":\"glob\",\"value\":\"svc.prod(?).*\"}"), true).test(event))
                .isTrue();
        assertThat(compiler.compile(json("{\"path\":\"host\",\"op\":\"glob\",\"value\":\"svcXprod(1).internal\"}"), true).test(event))
                .isFalse();
    }

    @Test
    void rejectsMalformedTreesAndReportsNodeLocation() {
        assertThatThrownBy(() -> compiler.compile(json("{\"and\":[{\"path\":\"a\",\"op\":\"equals\",\"value\":1}]}"), false))
                .isInstanceOf(InvalidConditionException.class)
                .hasMessageContaining("'equals'")
                .hasMessageContaining("$.and[0]");

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\"regex\",\"value\":\"[unclosed\"}"), false))
                .isInstanceOf(InvalidConditionException.class)
                .hasMessageContaining("Invalid regex")
                .hasMessageContaining("$");

        assertThatThrownBy(() -> compiler.compile(json("{\"and\":{}}"), false))
                .isInstanceOf(InvalidConditionException.class)
                .hasMessageContaining("$.and");

        assertThatThrownBy(() -> compiler.compile(json("{\"and\":[],\"or\":[]}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\"}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\"in\",\"value\":\"not-array\"}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\">\",\"value\":\"abc\"}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\"contains\",\"value\":123}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\"glob\",\"value\":123}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\"exists\",\"value\":\"yes\"}"), false))
                .isInstanceOf(InvalidConditionException.class);

        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\"==\",\"value\":null}"), false))
                .isInstanceOf(InvalidConditionException.class);
    }

    private static JsonNode loadFixture(String resourcePath) throws IOException {
        try (InputStream in = DefaultConditionCompilerTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as(resourcePath).isNotNull();
            return JSON.readTree(in);
        }
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
