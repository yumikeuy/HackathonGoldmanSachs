package com.hackathon.alerting.rules.alert;

import com.hackathon.alerting.runbooks.model.Runbook;

/**
 * Runbook reference on the alert card ({@code RunbookLink} in {@code frontend/openapi.yaml}).
 *
 * @param fileUrl where the UI downloads the file: {@code GET /api/runbooks/{id}/file} (Role 3)
 */
public record RunbookLink(long id, String fileName, String fileUrl) {

    /** Reads only metadata; the lazy file content is never touched. */
    public static RunbookLink from(Runbook runbook) {
        return new RunbookLink(runbook.getId(), runbook.getFileName(), "/api/runbooks/" + runbook.getId() + "/file");
    }
}
