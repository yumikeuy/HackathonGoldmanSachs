package com.hackathon.alerting.ingest.envelope;

import com.hackathon.alerting.ingest.model.SourceType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void serializesToTheSnakeCaseWireFormat() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-21T00:17:03.120Z"), ZoneOffset.UTC);
        EventEnvelope envelope = EventEnvelope.create(SourceType.HTTP, "payments-monitoring", JSON.readTree("{\"a\":1}"), clock);

        JsonNode wire = JSON.readTree(JSON.writeValueAsString(envelope));

        assertThat(wire.get("event_id").asString()).isEqualTo(envelope.eventId().toString());
        assertThat(wire.get("ingested_at").asString()).isEqualTo("2026-11-21T00:17:03.120Z");
        assertThat(wire.get("source_type").asString()).isEqualTo("http");
        assertThat(wire.get("source_name").asString()).isEqualTo("payments-monitoring");
        assertThat(wire.get("raw_payload")).isEqualTo(JSON.readTree("{\"a\":1}"));
        assertThat(JSON.readValue(wire.toString(), EventEnvelope.class)).isEqualTo(envelope);
    }
}
