package com.hackathon.alerting.rules.api;

import com.hackathon.alerting.delivery.model.Channel;
import com.hackathon.alerting.rules.model.GroupMode;
import com.hackathon.alerting.rules.model.Presentation;
import com.hackathon.alerting.rules.model.Rule;
import com.hackathon.alerting.rules.model.Severity;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** A rule as returned by the API ({@code Rule} in {@code frontend/openapi.yaml}). */
public record RuleDto(
        long id,
        String name,
        long teamId,
        JsonNode condition,
        int priority,
        boolean active,
        Severity assignedSeverity,
        List<Channel> channels,
        List<String> groupByPaths,
        GroupMode groupMode,
        int dedupWindowSec,
        Presentation presentation,
        JsonNode resolveCondition,
        Long overrideRunbookId,
        Instant updatedAt) {

    /** Maps a rule; team and runbook are read by id only (no query). */
    public static RuleDto from(Rule rule) {
        return new RuleDto(rule.getId(), rule.getName(), rule.getTeam().getId(), rule.getCondition(), rule.getPriority(),
                rule.isActive(), rule.getAssignedSeverity(), rule.getChannels(), rule.getGroupByPaths(), rule.getGroupMode(),
                rule.getDedupWindowSec(), rule.getPresentation(), rule.getResolveCondition(),
                rule.getOverrideRunbook() == null ? null : rule.getOverrideRunbook().getId(), rule.getUpdatedAt());
    }
}
