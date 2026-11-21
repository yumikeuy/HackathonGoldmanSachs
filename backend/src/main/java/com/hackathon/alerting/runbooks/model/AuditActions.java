package com.hackathon.alerting.runbooks.model;

/**
 * Well-known values of {@code audit_logs.action_type}.
 *
 * <p>The column is free-form on purpose: the {@code AuditService.log(alertId, actor, actionType, details)}
 * contract (AGENTS.md §8) takes a plain {@code String}, so any module can audit a new kind of action
 * without touching this module. Use these constants for the actions listed in AGENTS.md.
 */
public final class AuditActions {

    /** A user acknowledged the alert. */
    public static final String ACK = "ACK";
    /** A user resolved the alert manually. */
    public static final String RESOLVE = "RESOLVE";
    /** The alert was resolved by the rule's {@code resolve_condition}. */
    public static final String AUTO_CLOSE = "AUTO_CLOSE";
    /** A runbook was attached to an alert — when it opened, or later because runbooks or the rule changed. */
    public static final String RUNBOOK_ATTACHED = "RUNBOOK_ATTACHED";
    /** An open alert lost its runbook because none applies any more (deleted, or its condition changed). */
    public static final String RUNBOOK_DETACHED = "RUNBOOK_DETACHED";

    private AuditActions() {
    }
}
