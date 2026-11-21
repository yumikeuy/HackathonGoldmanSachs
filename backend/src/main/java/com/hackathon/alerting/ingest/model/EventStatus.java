package com.hackathon.alerting.ingest.model;

/** Why an event was stored in table {@code events} (the full stream is never stored there). */
public enum EventStatus {

    /** No team matched; an alert with severity UNKNOWN was created for the event. */
    UNROUTED,
    /** Processing failed and the message went to {@code events.ingest.DLT}; it can be replayed. */
    FAILED
}
