package com.hackathon.alerting.runbooks.audit;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.runbooks.model.AuditLog;
import com.hackathon.alerting.runbooks.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * JPA-backed {@link AuditService} (owner: Role 3): appends records to {@code audit_logs},
 * joining the caller's transaction so audit history commits or rolls back atomically with the action.
 */
@Service
public class DefaultAuditService implements AuditService {

    private final AuditLogRepository auditLogs;
    private final EntityManager entityManager;

    public DefaultAuditService(AuditLogRepository auditLogs, EntityManager entityManager) {
        this.auditLogs = auditLogs;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void log(Long alertId, String actor, String actionType, JsonNode details) {
        Alert alert = alertId == null ? null : entityManager.getReference(Alert.class, alertId);
        auditLogs.save(new AuditLog(alert, actor, actionType, details));
    }
}
