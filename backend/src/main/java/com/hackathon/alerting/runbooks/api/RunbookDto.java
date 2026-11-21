package com.hackathon.alerting.runbooks.api;

import com.hackathon.alerting.runbooks.model.Runbook;
import com.hackathon.alerting.runbooks.repository.RunbookSummary;
import tools.jackson.databind.JsonNode;

/**
 * Runbook metadata DTO returned by {@code /api/runbooks} endpoints
 * (matches schemas {@code RunbookLink} and {@code Runbook} in {@code frontend/openapi.yaml}).
 * Never exposes file bytes.
 */
public record RunbookDto(
        Long id,
        String fileName,
        String fileUrl,
        Long teamId,
        JsonNode condition
) {
    public static RunbookDto from(RunbookSummary summary) {
        return new RunbookDto(
                summary.id(),
                summary.fileName(),
                fileUrl(summary.id()),
                summary.teamId(),
                summary.condition()
        );
    }

    public static RunbookDto from(Runbook runbook) {
        Long teamId = runbook.getTeam() == null ? null : runbook.getTeam().getId();
        return new RunbookDto(
                runbook.getId(),
                runbook.getFileName(),
                fileUrl(runbook.getId()),
                teamId,
                runbook.getCondition()
        );
    }

    public static String fileUrl(long id) {
        return "/api/runbooks/" + id + "/file";
    }
}
