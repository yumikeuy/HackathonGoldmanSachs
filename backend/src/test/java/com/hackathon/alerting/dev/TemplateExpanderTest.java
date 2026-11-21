package com.hackathon.alerting.dev;

import com.hackathon.alerting.common.web.InvalidRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateExpanderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TemplateExpander expander =
            new TemplateExpander(Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void expandsPlaceholdersAtAnyDepthAndKeepsKeys() {
        JsonNode template = json("""
                {"id":"T-{{seq}}", "{{seq}}":"key stays", "at":"{{now}}",
                 "trade":{"legs":[{"ref":"{{uuid}}"}], "ccy":"{{oneOf:EUR|USD}}"}, "fixed":42, "flag":true}""");

        JsonNode event = expander.expand(template, 7);

        assertThat(event.get("id").asString()).isEqualTo("T-7");
        assertThat(event.get("{{seq}}").asString()).isEqualTo("key stays");
        assertThat(event.get("at").asString()).isEqualTo("2026-11-21T10:00:00Z");
        assertThat(event.at("/trade/legs/0/ref").asString()).matches("[0-9a-f-]{36}");
        assertThat(event.at("/trade/ccy").asString()).isIn("EUR", "USD");
        assertThat(event.get("fixed").asInt()).isEqualTo(42);
        assertThat(event.get("flag").asBoolean()).isTrue();
        assertThat(template.get("id").asString()).as("template untouched").isEqualTo("T-{{seq}}");
    }

    @Test
    void wholeValueNumericPlaceholdersBecomeNumbers() {
        JsonNode event = expander.expand(json("{\"n\":\"{{seq}}\",\"r\":\"{{randInt:5:5}}\",\"s\":\"#{{seq}}\"}"), 3);

        assertThat(event.get("n").isNumber()).isTrue();
        assertThat(event.get("n").asLong()).isEqualTo(3);
        assertThat(event.get("r").asLong()).isEqualTo(5);
        assertThat(event.get("s").asString()).isEqualTo("#3");
    }

    @Test
    void randomValuesStayWithinBounds() {
        JsonNode template = json("{\"v\":\"{{randInt:1:3}}\"}");
        for (int i = 0; i < 100; i++) {
            assertThat(expander.expand(template, i).get("v").asLong()).isBetween(1L, 3L);
        }
    }

    @Test
    void rejectsUnknownAndMalformedPlaceholders() {
        assertThatThrownBy(() -> expander.validate(json("{\"a\":\"{{random}}\"}"))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("unknown placeholder");
        assertThatThrownBy(() -> expander.validate(json("{\"a\":\"{{randInt:9:1}}\"}"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> expander.validate(json("{\"a\":\"{{randInt:x:1}}\"}"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> expander.validate(json("{\"a\":[\"{{randInt}}\"]}"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> expander.validate(json("{\"a\":\"{{seq:1}}\"}"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> expander.validate(json("{\"a\":\"{{oneOf:}}\"}"))).isInstanceOf(InvalidRequestException.class);
        expander.validate(json("{\"a\":\"plain {braces} and {{seq}}\",\"b\":1}"));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
