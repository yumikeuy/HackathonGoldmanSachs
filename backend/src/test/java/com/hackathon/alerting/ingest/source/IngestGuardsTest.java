package com.hackathon.alerting.ingest.source;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestGuardsTest {

    private final PayloadParser parser = new PayloadParser(DataSize.ofKilobytes(1), 5);

    @Test
    void rejectsJsonNestedDeeperThanTheLimit() {
        assertThat(parser.parse("{\"a\":{\"b\":{\"c\":1}}}").at("/a/b/c").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> parser.parse("[[[[[[[1]]]]]]]")).isInstanceOf(JacksonException.class);
    }

    @Test
    void lenientParsingKeepsBadInputAsTruncatedText() {
        assertThat(parser.parseOrText("plain log line").asString()).isEqualTo("plain log line");
        assertThat(parser.parseOrText("[[[[[[[1]]]]]]]").isString()).isTrue();
        assertThat(parser.parseOrText("x".repeat(5000)).asString()).hasSize(1024);
        assertThat(parser.parseOrText(null).isNull()).isTrue();
    }

    @Test
    void apiKeysAreStoredAsHashesAndComparedSafely() {
        String key = SourceKeys.generate();
        assertThat(key).startsWith("src_").hasSizeGreaterThan(40);
        assertThat(SourceKeys.generate()).isNotEqualTo(key);

        ObjectNode config = JsonNodeFactory.instance.objectNode().put(SourceKeys.HASH_FIELD, SourceKeys.hash(key));
        assertThat(SourceKeys.isRequired(config)).isTrue();
        assertThat(SourceKeys.matches(config, key)).isTrue();
        assertThat(SourceKeys.matches(config, key + "x")).isFalse();
        assertThat(SourceKeys.matches(config, null)).isFalse();
        assertThat(config.toString()).doesNotContain(key);

        JsonNode open = JsonNodeFactory.instance.objectNode().put("topic", "t");
        assertThat(SourceKeys.isRequired(open)).isFalse();
        assertThat(SourceKeys.matches(open, null)).isTrue();
        assertThat(SourceKeys.withoutSecrets(config).has(SourceKeys.HASH_FIELD)).isFalse();
        assertThat(config.has(SourceKeys.HASH_FIELD)).as("original untouched").isTrue();
    }
}
