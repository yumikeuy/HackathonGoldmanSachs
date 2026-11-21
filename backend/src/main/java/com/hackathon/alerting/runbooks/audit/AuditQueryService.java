package com.hackathon.alerting.runbooks.audit;

import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.rules.repository.AlertRepository;
import com.hackathon.alerting.runbooks.api.AuditEntry;
import com.hackathon.alerting.runbooks.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only query service for an alert's audit history (owner: Role 3).
 */
@Service
public class AuditQueryService {

    private final AlertRepository alerts;
    private final AuditLogRepository auditLogs;

    public AuditQueryService(AlertRepository alerts, AuditLogRepository auditLogs) {
        this.alerts = alerts;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> findByAlertId(long alertId) {
        if (!alerts.existsById(alertId)) {
            throw new NotFoundException("Alert", alertId);
        }
        return auditLogs.findAllByAlertIdOrderByCreatedAtAscIdAsc(alertId).stream()
                .map(AuditEntry::from)
                .toList();
    }
}
