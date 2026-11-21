package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.AlertStatus;
import com.hackathon.alerting.rules.model.Severity;

import java.time.Instant;

/**
 * Feed row and WebSocket message body ({@code AlertSummary} in {@code frontend/openapi.yaml}).
 *
 * @param teamId  {@code null} for UNROUTED alerts
 * @param ruleId  {@code null} for UNROUTED alerts
 * @param runbookId attached runbook, if any
 */
public record AlertSummary(
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
        Long runbookId) {

    /**
     * Maps an alert. Reads the rule's name, so the rule must be fetched or the call must run inside a
     * transaction; team and runbook are only read by id (no query).
     */
    public static AlertSummary from(Alert alert) {
        return new AlertSummary(
                alert.getId(),
                alert.getTeam() == null ? null : alert.getTeam().getId(),
                alert.getRule() == null ? null : alert.getRule().getId(),
                alert.getRule() == null ? null : alert.getRule().getName(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getTitle(),
                alert.getOccurrencesCount(),
                alert.getFirstSeenAt(),
                alert.getLastSeenAt(),
                alert.getResolvedAt(),
                alert.getRunbook() == null ? null : alert.getRunbook().getId());
    }
}
