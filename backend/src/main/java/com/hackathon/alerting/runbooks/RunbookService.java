package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.rules.model.Alert;
import tools.jackson.databind.JsonNode;

/**
 * Contract (AGENTS.md §8, owner Role 3): choose and attach a runbook to a newly created alert.
 *
 * <p>Called by Role 2's {@code AlertCreator} inside the alert-creation transaction, right after the alert
 * row exists. Selection order (AGENTS.md §6, step 8): the rule's {@code override_runbook_id}; else a runbook
 * of the alert's team whose condition matches the event; else a matching global runbook; ties → lower id.
 * On success sets {@code alert.runbook} ({@link Alert#attachRunbook}) and writes audit {@code RUNBOOK_ATTACHED}.
 *
 * <p>Must use the runbook cache (no per-event queries) and must not throw for "nothing matched".
 */
public interface RunbookService {

    /**
     * @param alert managed, persisted alert (has an id); UNROUTED alerts have no rule and no team
     * @param event the event that opened the alert
     */
    void attach(Alert alert, JsonNode event);
}
