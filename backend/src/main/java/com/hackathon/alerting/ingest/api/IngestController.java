package com.hackathon.alerting.ingest.api;

import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.source.EventIngestor;
import com.hackathon.alerting.ingest.source.SourceCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/ingest/{source}}: accepts an event of any JSON shape from an active source and answers
 * 202 only after Kafka acknowledged it; 400 for a body that is not JSON, 404 for an unknown or inactive
 * source, 503 if Kafka is unavailable.
 */
@RestController
public class IngestController {

    private final SourceCache sources;
    private final EventIngestor ingestor;
    private final JsonMapper json;
    private final Counter invalidJson;
    private final Counter unknownSource;

    public IngestController(SourceCache sources, EventIngestor ingestor, JsonMapper json, MeterRegistry metrics) {
        this.sources = sources;
        this.ingestor = ingestor;
        this.json = json;
        this.invalidJson = EventIngestor.rejected(metrics, "invalid_json");
        this.unknownSource = EventIngestor.rejected(metrics, "unknown_source");
    }

    @PostMapping(path = "/api/ingest/{source}", consumes = "*/*")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestAccepted ingest(@PathVariable String source, @RequestBody(required = false) String body) {
        SourceCache.CachedSource cached = sources.findActive(source).orElseThrow(() -> {
            unknownSource.increment();
            return new NotFoundException("Source", source);
        });
        EventEnvelope envelope = ingestor.ingest(SourceType.HTTP, cached.name(), parse(body));
        return new IngestAccepted(envelope.eventId(), envelope.ingestedAt());
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            invalidJson.increment();
            throw new InvalidRequestException("Request body must be a JSON value");
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            invalidJson.increment();
            throw new InvalidRequestException("Request body is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** {@code IngestAccepted} of openapi.yaml. */
    public record IngestAccepted(UUID eventId, Instant ingestedAt) {
    }
}
