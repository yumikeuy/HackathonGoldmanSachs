package com.hackathon.alerting.ingest.source;

import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class LogLineFormatTest {

    private final PayloadParser parser = new PayloadParser(DataSize.ofMegabytes(1), 64);
    private final LogLineFormat format = LogLineFormat.of(TestData.json("""
            {"path": "/data/logs/app.log",
             "pattern": "(?<ts>\\\\S+) (?<level>[A-Z]+) (?<service>[\\\\w-]+): instrument=(?<instrument>\\\\S+) (?<text>.*)"}
            """));

    @Test
    void namedGroupsBecomeFieldsAndTheLineIsKeptAsMessage() {
        String line = "2026-11-21T10:00:00Z ERROR pricing-engine: instrument=EUR-IRS-10Y curve load timed out";
        JsonNode event = format.toPayload(line, parser);

        assertThat(event.get("level").asString()).isEqualTo("ERROR");
        assertThat(event.get("service").asString()).isEqualTo("pricing-engine");
        assertThat(event.get("instrument").asString()).isEqualTo("EUR-IRS-10Y");
        assertThat(event.get("text").asString()).isEqualTo("curve load timed out");
        assertThat(event.get("message").asString()).isEqualTo(line);
    }

    @Test
    void nonMatchingLinesAndJsonLinesKeepTheirDefaultShape() {
        assertThat(format.toPayload("free text", parser)).isEqualTo(TestData.json("{\"message\":\"free text\"}"));
        assertThat(format.toPayload("{\"level\":\"WARN\"}", parser).get("level").asString()).isEqualTo("WARN");
        assertThat(new LogLineFormat(null).toPayload("x", parser).get("message").asString()).isEqualTo("x");
    }

    @Test
    void validatesThePatternOnSave() {
        assertThat(LogLineFormat.validate(TestData.json("{\"path\":\"/a\"}"))).isEmpty();
        assertThat(LogLineFormat.validate(TestData.json("{\"pattern\":\"(?<level>[A-Z]+)\"}"))).isEmpty();
        assertThat(LogLineFormat.validate(TestData.json("{\"pattern\":\"([A-Z]+)\"}"))).get().asString().contains("named group");
        assertThat(LogLineFormat.validate(TestData.json("{\"pattern\":\"(?<x>[\"}"))).get().asString().contains("not a valid regex");
        assertThat(LogLineFormat.validate(TestData.json("{\"pattern\":5}"))).isPresent();
    }
}
