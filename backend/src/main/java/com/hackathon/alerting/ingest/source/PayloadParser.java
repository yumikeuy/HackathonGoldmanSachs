package com.hackathon.alerting.ingest.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Parses incoming event payloads with hard limits, so a single hostile event cannot exhaust memory or the stack
 * (docs/security.md): at most {@code alerting.ingest.max-body-size} bytes (1 MB) and
 * {@code alerting.ingest.max-json-depth} levels of nesting (64). Used by every source type.
 */
@Component
public class PayloadParser {

    private final JsonMapper json;
    private final int maxBytes;

    public PayloadParser(@Value("${alerting.ingest.max-body-size:1MB}") DataSize maxBodySize,
                         @Value("${alerting.ingest.max-json-depth:64}") int maxDepth) {
        this.maxBytes = (int) Math.min(Integer.MAX_VALUE - 8, maxBodySize.toBytes());
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(maxDepth)
                .maxDocumentLength(maxBytes)
                .build();
        this.json = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(constraints).build()).build();
    }

    /** Largest accepted payload in bytes. */
    public int maxBytes() {
        return maxBytes;
    }

    /**
     * Strict parse for HTTP: the body must be one JSON value within the limits.
     *
     * @throws JacksonException if it is not JSON or breaks a limit
     */
    public JsonNode parse(String text) {
        return json.readTree(text);
    }

    /**
     * Lenient parse for Kafka and file sources: JSON within the limits as is, anything else (text, broken or oversized
     * JSON) as a JSON string truncated to the size limit — the event is kept, never dropped.
     */
    public JsonNode parseOrText(String text) {
        if (text == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return JsonNodeFactory.instance.stringNode(text.length() > maxBytes ? text.substring(0, maxBytes) : text);
        }
    }
}
