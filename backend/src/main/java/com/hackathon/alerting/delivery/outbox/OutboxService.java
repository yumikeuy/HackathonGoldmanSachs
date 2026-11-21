package com.hackathon.alerting.delivery.outbox;

import com.hackathon.alerting.rules.model.Alert;
import com.hackathon.alerting.rules.model.Rule;

/**
 * Contract (AGENTS.md §8, owner Role 1): queue notifications for a newly created alert.
 *
 * <p>Called by Role 2's {@code AlertCreator} inside the alert-creation transaction (after
 * {@code RunbookService.attach}, so the runbook link can go into the message). For each channel of the
 * rule and each active user of the team it picks the channel by the user's preferences, working hours
 * and time zone and inserts {@code delivery_outbox} rows; {@code UI} becomes one row with no user.
 * Sending happens later in the {@code OutboxWorker}; this method never calls providers.
 *
 * <p>Must read users from the cache, not the database, and must not be called for UNROUTED alerts.
 */
public interface OutboxService {

    /**
     * @param alert managed, persisted alert of {@code rule}
     * @param rule  the rule that produced the alert (channels, presentation)
     */
    void enqueue(Alert alert, Rule rule);
}
