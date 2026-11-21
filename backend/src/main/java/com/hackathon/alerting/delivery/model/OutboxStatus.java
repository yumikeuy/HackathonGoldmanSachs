package com.hackathon.alerting.delivery.model;

/** State of a {@code delivery_outbox} row. */
public enum OutboxStatus {

    /** Waiting to be sent or for the next attempt ({@code next_retry_at}). */
    PENDING,
    /** The provider confirmed delivery. */
    SENT,
    /** Retries exhausted. */
    FAILED
}
