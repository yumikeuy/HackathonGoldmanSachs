package com.hackathon.alerting.runbooks.api;

import com.hackathon.alerting.runbooks.audit.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Audit trail endpoint ({@code GET /api/alerts/{id}/audit}, tag {@code alerts} in {@code frontend/openapi.yaml}).
 */
@RestController
@Tag(name = "alerts")
public class AuditController {

    private final AuditQueryService auditQueries;

    public AuditController(AuditQueryService auditQueries) {
        this.auditQueries = auditQueries;
    }

    @GetMapping("/api/alerts/{id}/audit")
    @Operation(summary = "Alert history, oldest first")
    public List<AuditEntry> getAlertAudit(@PathVariable long id) {
        return auditQueries.findByAlertId(id);
    }
}
