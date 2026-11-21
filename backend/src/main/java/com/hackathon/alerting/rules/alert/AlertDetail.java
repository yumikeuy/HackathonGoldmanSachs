package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import com.hackathon.alerting.rules.model.Severity;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * The alert card ({@code AlertDetail} in {@code frontend/openapi.yaml}): every {@link AlertSummary} field
 * plus the first event of the group and the runbook link.
 */
public record AlertDetail(
        long id,
        Long teamId,
        Long ruleId,
        String ruleName,
        Severity severity,
        AlertStatus status,
        String title,
        int occurrencesCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant resolvedAt,
        Long runbookId,
        JsonNode samplePayload,
        RunbookLink runbook) {

    /** Maps an alert; rule and runbook are read, so fetch them or call inside a transaction. */
    public static AlertDetail from(Alert alert) {
        AlertSummary s = AlertSummary.from(alert);
        return new AlertDetail(s.id(), s.teamId(), s.ruleId(), s.ruleName(), s.severity(), s.status(), s.title(),
                s.occurrencesCount(), s.firstSeenAt(), s.lastSeenAt(), s.resolvedAt(), s.runbookId(),
                alert.getSamplePayload(), alert.getRunbook() == null ? null : RunbookLink.from(alert.getRunbook()));
    }

    /** The summary part, e.g. to push the change to the feed. */
    public AlertSummary summary() {
        return new AlertSummary(id, teamId, ruleId, ruleName, severity, status, title, occurrencesCount,
                firstSeenAt, lastSeenAt, resolvedAt, runbookId);
    }
}
