package com.hackathon.alerting.delivery.model;

/**
 * Notification delivery channel. Used in {@code rules.channels}, {@code users.channel_preferences}
 * and {@code delivery_outbox.channel}; stored in the database and JSON as the constant name.
 */
public enum Channel {

    EMAIL,
    SMS,
    /** Voice call (simulated). */
    CALL,
    /** Team feed in the UI over WebSocket; addressed to a team, not to a user. */
    UI;

    /** Whether the channel targets a specific user. UI does not: it is one outbox row per team. */
    public boolean isPersonal() {
        return this != UI;
    }
}
