package com.hackathon.alerting.runbooks.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * STEP-0 STUB of {@link AuditService} (owner Role 3): writes to the application log only.
 * Role 3 replaces the body with an insert into {@code audit_logs} (AuditLogRepository).
 */
@Service
public class DefaultAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditService.class);

    @Override
    public void log(Long alertId, String actor, String actionType, JsonNode details) {
        log.info("audit alert={} actor={} action={} details={}", alertId, actor, actionType, details);
    }
}
