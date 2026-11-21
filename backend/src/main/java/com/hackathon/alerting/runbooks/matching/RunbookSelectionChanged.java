package com.hackathon.alerting.runbooks.matching;

import java.util.Objects;

/**
 * Something that decides which runbook an alert gets has changed: a runbook was created, edited or is about to be
 * deleted, or a rule's "always attach" runbook changed. Published as a Spring application event <b>inside the
 * transaction</b> that makes the change; {@link RunbookResync} handles it synchronously in that same transaction.
 *
 * @param trigger          what changed, for the audit trail, e.g. {@code "runbook 7 updated"}
 * @param removedRunbookId the runbook being deleted — publish <b>before</b> deleting it — or {@code null}
 */
public record RunbookSelectionChanged(String trigger, Long removedRunbookId) {

    public RunbookSelectionChanged {
        Objects.requireNonNull(trigger, "trigger");
    }

    public static RunbookSelectionChanged created(long runbookId) {
        return new RunbookSelectionChanged("runbook " + runbookId + " created", null);
    }

    public static RunbookSelectionChanged updated(long runbookId) {
        return new RunbookSelectionChanged("runbook " + runbookId + " updated", null);
    }

    public static RunbookSelectionChanged deleting(long runbookId) {
        return new RunbookSelectionChanged("runbook " + runbookId + " deleted", runbookId);
    }

    public static RunbookSelectionChanged ruleOverrideChanged(long ruleId) {
        return new RunbookSelectionChanged("rule " + ruleId + " always-attach runbook changed", null);
    }
}
