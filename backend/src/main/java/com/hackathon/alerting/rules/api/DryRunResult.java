package com.hackathon.alerting.rules.api;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Result of a dry run ({@code DryRunResult} in {@code frontend/openapi.yaml}).
 *
 * @param checked recent events the condition was evaluated against
 * @param matched how many of them matched
 * @param samples up to {@code sampleSize} matching raw events, newest first
 */
public record DryRunResult(int checked, int matched, List<JsonNode> samples) {
}
