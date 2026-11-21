package com.hackathon.alerting.rules.api;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.rules.model.GroupMode;
import com.hackathon.alerting.rules.model.Presentation;
import com.hackathon.alerting.rules.model.Severity;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Body of {@code POST /api/rules} and {@code PUT /api/rules/{id}} ({@code RuleInput} in {@code frontend/openapi.yaml}).
 * Omitted optional fields take the documented defaults; {@code PUT} replaces the whole rule.
 */
public record RuleInput(
        @NotBlank @Size(max = 200) String name,
        @NotNull Long teamId,
        JsonNode condition,
        Integer priority,
        Boolean active,
        Severity assignedSeverity,
        List<@NotNull Channel> channels,
        List<@NotBlank String> groupByPaths,
        GroupMode groupMode,
        @Min(1) Integer dedupWindowSec,
        Presentation presentation,
        JsonNode resolveCondition,
        Long overrideRunbookId) {
}
