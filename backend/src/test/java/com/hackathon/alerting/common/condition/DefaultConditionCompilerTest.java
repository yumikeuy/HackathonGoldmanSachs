package com.hackathon.alerting.common.condition;

import com.hackathon.alerting.common.json.ExactPathFieldLocator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins the behaviour of the step-0 stub that Roles 1 and 2 develop against. Role 3 extends these tests. */
class DefaultConditionCompilerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConditionCompiler compiler = new DefaultConditionCompiler(new ExactPathFieldLocator());

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
    void rejectsMalformedTreesAndUnimplementedOperators() {
        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\",\"op\":\">\",\"value\":1}"), false))
                .isInstanceOf(InvalidConditionException.class).hasMessageContaining("'>'");
        assertThatThrownBy(() -> compiler.compile(json("{\"and\":{}}"), false)).isInstanceOf(InvalidConditionException.class);
        assertThatThrownBy(() -> compiler.compile(json("{\"path\":\"a\"}"), false)).isInstanceOf(InvalidConditionException.class);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
