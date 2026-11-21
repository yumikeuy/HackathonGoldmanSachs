package com.hackathon.alerting.ingest.api;

import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.common.web.PayloadTooLargeException;
import com.hackathon.alerting.common.web.TooManyRequestsException;
import com.hackathon.alerting.common.web.UnauthorizedException;
import com.hackathon.alerting.ingest.envelope.EventEnvelope;
import com.hackathon.alerting.ingest.model.SourceType;
import com.hackathon.alerting.ingest.source.EventIngestor;
import com.hackathon.alerting.ingest.source.IngestRateLimiter;
import com.hackathon.alerting.ingest.source.PayloadParser;
import com.hackathon.alerting.ingest.source.SourceCache;
import com.hackathon.alerting.ingest.source.SourceKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/ingest/{source}}: accepts an event of any JSON shape from an active source and answers 202 only
 * after Kafka acknowledged it.
 *
 * <p>Checks, in order (docs/security.md): 404 unknown or inactive source; 401 if the source requires an API key and
 * {@code X-Api-Key} is missing or wrong; 429 over the source's rate limit; 413 body larger than
 * {@code alerting.ingest.max-body-size}; 400 not JSON or nested deeper than {@code alerting.ingest.max-json-depth};
 * 503 if Kafka is unavailable.
 */
@RestController
public class IngestController {

    private final SourceCache sources;
    private final EventIngestor ingestor;
    private final PayloadParser parser;
    private final IngestRateLimiter rateLimiter;
    private final Counter invalidJson;
    private final Counter unknownSource;
    private final Counter unauthorized;
    private final Counter rateLimited;
    private final Counter tooLarge;

    public IngestController(SourceCache sources, EventIngestor ingestor, PayloadParser parser,
                            IngestRateLimiter rateLimiter, MeterRegistry metrics) {
        this.sources = sources;
        this.ingestor = ingestor;
        this.parser = parser;
        this.rateLimiter = rateLimiter;
        this.invalidJson = EventIngestor.rejected(metrics, "invalid_json");
        this.unknownSource = EventIngestor.rejected(metrics, "unknown_source");
        this.unauthorized = EventIngestor.rejected(metrics, "unauthorized");
        this.rateLimited = EventIngestor.rejected(metrics, "rate_limited");
        this.tooLarge = EventIngestor.rejected(metrics, "too_large");
    }

    @PostMapping(path = "/api/ingest/{source}", consumes = "*/*")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestAccepted ingest(@PathVariable String source,
                                 @RequestHeader(name = SourceKeys.HEADER, required = false) String apiKey,
                                 InputStream body) throws IOException {
        SourceCache.CachedSource cached = sources.findActive(source).orElseThrow(() -> {
            unknownSource.increment();
            return new NotFoundException("Source", source);
        });
        if (!SourceKeys.matches(cached.config(), apiKey)) {
            unauthorized.increment();
            throw new UnauthorizedException("Source '" + source + "' requires a valid " + SourceKeys.HEADER + " header");
        }
        if (!rateLimiter.tryAcquire(cached.name())) {
            rateLimited.increment();
            throw new TooManyRequestsException("Source '" + source + "' sends more than " + rateLimiter.perSecond()
                    + " events per second", Duration.ofSeconds(1));
        }
        EventEnvelope envelope = ingestor.ingest(SourceType.HTTP, cached.name(), parse(read(body)));
        return new IngestAccepted(envelope.eventId(), envelope.ingestedAt());
    }

    /** Reads at most the size limit; one byte more means the body is too large. */
    private String read(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(parser.maxBytes() + 1);
        if (bytes.length > parser.maxBytes()) {
            tooLarge.increment();
            throw new PayloadTooLargeException("Event body is larger than " + parser.maxBytes() + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private JsonNode parse(String body) {
        if (body.isBlank()) {
            invalidJson.increment();
            throw new InvalidRequestException("Request body must be a JSON value");
        }
        try {
            return parser.parse(body);
        } catch (JacksonException e) {
            invalidJson.increment();
            throw new InvalidRequestException("Request body is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** {@code IngestAccepted} of openapi.yaml. */
    public record IngestAccepted(UUID eventId, Instant ingestedAt) {
    }
}
