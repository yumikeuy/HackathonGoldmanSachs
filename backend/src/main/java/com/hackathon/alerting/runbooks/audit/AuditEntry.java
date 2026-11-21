package com.hackathon.alerting.runbooks.audit;

import com.hackathon.alerting.runbooks.model.AuditLog;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Audit trail entry returned by {@code GET /api/alerts/{id}/audit}
 * (matches schema {@code AuditEntry} in {@code frontend/openapi.yaml}).
 */
public record AuditEntry(
        Long id,
        Long alertId,
        String actor,
        String actionType,
        JsonNode details,
        Instant createdAt
) {
    public static AuditEntry from(AuditLog entry) {
        Long alertId = entry.getAlert() == null ? null : entry.getAlert().getId();
        return new AuditEntry(
                entry.getId(),
                alertId,
                entry.getActor(),
                entry.getActionType(),
                entry.getDetails(),
                entry.getCreatedAt()
        );
    }
}
