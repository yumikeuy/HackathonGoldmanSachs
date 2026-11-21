package com.hackathon.alerting.rules.model;

/**
 * Alert severity. Assigned by the rule ({@code rules.assigned_severity}), never taken from the event.
 * Declared in ascending order, so {@link #compareTo} orders by severity.
 */
public enum Severity {

    /** Only for UNROUTED alerts (no team matched); a rule cannot assign it. */
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** Whether a rule may assign this severity (everything except {@link #UNKNOWN}). */
    public boolean isAssignableByRule() {
        return this != UNKNOWN;
    }
}
