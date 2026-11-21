package com.hackathon.alerting.runbooks.audit;

import tools.jackson.databind.JsonNode;

/**
 * Contract (AGENTS.md §8, owner Role 3; called by every module): append a record to {@code audit_logs}.
 *
 * <p>Joins the caller's transaction, so the record commits or rolls back together with the change it describes.
 */
public interface AuditService {

    /**
     * @param alertId    the alert the action concerns, or {@code null}
     * @param actor      user email, {@code AuditLog.SYSTEM_ACTOR} or {@code AuditLog.ruleActor(id)}
     * @param actionType see {@code AuditActions}; free-form, up to 50 characters
     * @param details    extra data as a JSON object, or {@code null}
     */
    void log(Long alertId, String actor, String actionType, JsonNode details);
}
