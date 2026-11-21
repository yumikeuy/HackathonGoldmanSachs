package com.hackathon.alerting.runbooks.repository;

import com.hackathon.alerting.runbooks.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Access to {@code audit_logs}. Records are append-only ({@link AuditLog} is immutable). */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * An alert's history in chronological order ({@code GET /api/alerts/{id}/audit}).
     * Served by index {@code idx_audit_alert_time}.
     */
    List<AuditLog> findAllByAlertIdOrderByCreatedAtAscIdAsc(Long alertId);
}
