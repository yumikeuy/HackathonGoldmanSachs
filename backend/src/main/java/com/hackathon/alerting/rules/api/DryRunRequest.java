package com.hackathon.alerting.rules.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/rules/dry-run} ({@code DryRunRequest} in {@code frontend/openapi.yaml}).
 *
 * @param condition  the condition to try; {@code null} matches every event
 * @param teamId     if set: only events of this team count and the team's strict flag applies
 * @param strict     strict mode when {@code teamId} is not set; default {@code false}
 * @param sampleSize how many matching events to return; default {@value DryRunService#DEFAULT_SAMPLE_SIZE}
 */
public record DryRunRequest(JsonNode condition, Long teamId, Boolean strict, @Min(0) @Max(50) Integer sampleSize) {
}
