package com.hackathon.alerting.dev;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Body of {@code POST /api/dev/generate} ({@code GenerateRequest} in {@code frontend/openapi.yaml}).
 *
 * @param source        name of an active source to ingest under
 * @param count         number of events, default {@value #DEFAULT_COUNT}
 * @param ratePerSecond events per second, default {@value #DEFAULT_RATE}
 * @param template      event to send, with placeholders ({@link TemplateExpander}); {@code null} cycles the built-in samples
 */
public record GenerateRequest(
        @NotBlank String source,
        @Min(1) @Max(MAX_COUNT) Integer count,
        @Min(1) @Max(MAX_RATE) Integer ratePerSecond,
        JsonNode template) {

    public static final int DEFAULT_COUNT = 100;
    public static final int DEFAULT_RATE = 100;
    public static final int MAX_COUNT = 100_000;
    public static final int MAX_RATE = 1_000;

    public int countOrDefault() {
        return Objects.requireNonNullElse(count, DEFAULT_COUNT);
    }

    public int rateOrDefault() {
        return Objects.requireNonNullElse(ratePerSecond, DEFAULT_RATE);
    }

    /** The template, or {@code null} when absent or JSON {@code null}. */
    public JsonNode templateOrNull() {
        return template == null || template.isNull() ? null : template;
    }
}
