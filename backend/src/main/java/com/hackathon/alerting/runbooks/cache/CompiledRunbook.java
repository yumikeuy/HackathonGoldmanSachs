package com.hackathon.alerting.runbooks.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A runbook entry in {@link RunbookCache} with its optional compiled match predicate.
 *
 * <p>When {@link #condition()} is {@code null}, the runbook must never auto-match incoming events
 * and is kept in {@link RunbookCatalog#byId()} solely for explicit {@code rules.override_runbook_id}
 * lookups.
 *
 * @param id        runbook primary key
 * @param teamId    owning team id, or {@code null} for a global runbook
 * @param fileName  original file name (recorded in {@code RUNBOOK_ATTACHED} audit details)
 * @param condition compiled condition predicate, or {@code null} for override-only runbooks
 */
public record CompiledRunbook(
        long id,
        Long teamId,
        String fileName,
        Predicate<JsonNode> condition
) {

    private static final Logger log = LoggerFactory.getLogger(CompiledRunbook.class);

    public CompiledRunbook {
        Objects.requireNonNull(fileName, "fileName");
    }

    /** Whether this runbook is global ({@code team_id IS NULL}). */
    public boolean isGlobal() {
        return teamId == null;
    }

    /** Whether this runbook has a compiled condition and participates in automatic matching. */
    public boolean isAutoMatchable() {
        return condition != null;
    }

    /**
     * Evaluates the compiled condition against {@code event}. An override-only entry or any runtime
     * exception is treated as a non-match so one broken condition never halts alert processing.
     */
    public boolean matches(JsonNode event) {
        if (condition == null || event == null) {
            return false;
        }
        try {
            return condition.test(event);
        } catch (RuntimeException e) {
            log.warn("Condition evaluation threw on runbook id={} ({}); treating as non-match", id, fileName, e);
            return false;
        }
    }
}
