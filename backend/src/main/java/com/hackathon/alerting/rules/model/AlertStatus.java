package com.hackathon.alerting.rules.model;

/** Alert lifecycle: {@code OPEN → ACKED → RESOLVED} or directly {@code OPEN → RESOLVED}. */
public enum AlertStatus {

    OPEN,
    /** Someone from the team acknowledged the alert; it still collects repeats. */
    ACKED,
    /** Closed manually or by {@code resolve_condition}; the next matching event opens a new alert. */
    RESOLVED;

    /** OPEN and ACKED are "open": at most one such alert per fingerprint (partial unique index). */
    public boolean isOpen() {
        return this != RESOLVED;
    }
}
