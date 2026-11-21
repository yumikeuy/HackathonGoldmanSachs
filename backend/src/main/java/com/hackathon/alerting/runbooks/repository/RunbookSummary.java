package com.hackathon.alerting.runbooks.repository;

import tools.jackson.databind.JsonNode;

/**
 * Runbook metadata without the file bytes, for listings and the runbook cache.
 *
 * @param id        runbook id
 * @param teamId    owning team, {@code null} for a global runbook
 * @param fileName  original file name
 * @param condition when to attach the runbook; {@code null} means only via {@code rules.override_runbook_id}
 */
public record RunbookSummary(Long id, Long teamId, String fileName, JsonNode condition) {

    public boolean isGlobal() {
        return teamId == null;
    }
}
