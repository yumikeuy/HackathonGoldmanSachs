package com.hackathon.alerting.ingest.source;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How a file source turns a line into an event (contract: {@code SourceInput.config} in {@code openapi.yaml}):
 * <ol>
 *   <li>a JSON object/array line is ingested as is;</li>
 *   <li>if the source has {@code config.pattern} — a Java regex with <b>named groups</b> — and the line matches, each
 *       named group becomes a field, plus {@code message} = the whole line (unless a group is called {@code message}):
 *       {@code (?<ts>\S+) (?<level>[A-Z]+) (?<service>[\w-]+): (?<text>.*)} →
 *       {@code {"ts": …, "level": "ERROR", "service": "pricing-engine", "text": …, "message": "<line>"}};</li>
 *   <li>anything else → {@code {"message": "<line>"}}.</li>
 * </ol>
 * Values are strings; flexible conditions compare numeric strings as numbers (AGENTS.md §5).
 *
 * @param pattern compiled {@code config.pattern}, or {@code null}
 */
public record LogLineFormat(Pattern pattern) {

    public static final String PATTERN_FIELD = "pattern";

    /** The format of a source config; {@code pattern} must already be valid (checked on save). */
    public static LogLineFormat of(JsonNode config) {
        return new LogLineFormat(patternText(config).map(Pattern::compile).orElse(null));
    }

    /**
     * Validates {@code config.pattern} for the sources API.
     *
     * @return an error message, or empty if there is no pattern or it is valid
     */
    public static Optional<String> validate(JsonNode config) {
        Optional<String> text = patternText(config);
        if (text.isEmpty()) {
            JsonNode raw = config == null ? null : config.get(PATTERN_FIELD);
            return raw == null || raw.isNull() ? Optional.empty() : Optional.of("config.pattern must be a non-empty string");
        }
        try {
            if (Pattern.compile(text.get()).namedGroups().isEmpty()) {
                return Optional.of("config.pattern needs at least one named group, e.g. (?<level>[A-Z]+)");
            }
            return Optional.empty();
        } catch (PatternSyntaxException e) {
            return Optional.of("config.pattern is not a valid regex: " + e.getDescription());
        }
    }

    /** {@code config.pattern} as text, if set. */
    public static Optional<String> patternText(JsonNode config) {
        JsonNode value = config == null ? null : config.get(PATTERN_FIELD);
        return value != null && value.isString() && !value.asString().isBlank() ? Optional.of(value.asString()) : Optional.empty();
    }

    /** The event for one line. */
    public JsonNode toPayload(String line, PayloadParser parser) {
        String trimmed = line.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonNode parsed = parser.parseOrText(trimmed);
            if (!parsed.isString()) {
                return parsed;
            }
        }
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        if (pattern != null) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                for (Map.Entry<String, Integer> group : pattern.namedGroups().entrySet()) {
                    String value = matcher.group(group.getValue());
                    if (value != null) {
                        event.put(group.getKey(), value);
                    }
                }
            }
        }
        if (!event.has("message")) {
            event.put("message", line);
        }
        return event;
    }
}
