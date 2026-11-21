package com.hackathon.alerting.runbooks.model;

/**
 * Kind of audited action ({@code audit_logs.action_type}). Stored as the constant name.
 * Add a constant here when a module starts auditing a new action.
 */
public enum AuditAction {

    /** A user acknowledged the alert. */
    ACK,
    /** A user resolved the alert manually. */
    RESOLVE,
    /** The alert was resolved by the rule's {@code resolve_condition}. */
    AUTO_CLOSE,
    /** A runbook was attached to a new alert. */
    RUNBOOK_ATTACHED
}
