package com.hackathon.alerting.common.config;

/**
 * STOMP destinations pushed to the UI over {@code /ws} (AGENTS.md §7). Message bodies are JSON;
 * their schemas are in {@code frontend/openapi.yaml} ({@code AlertSummary}, {@code Delivery}).
 */
public final class WebSocketTopics {

    /** Prefix of the in-memory broker. */
    public static final String PREFIX = "/topic";
    /** UNROUTED alerts (severity UNKNOWN). Role 2. */
    public static final String UNROUTED = PREFIX + "/unrouted";
    /** Delivery attempts from the simulator. Role 1. */
    public static final String SIMULATOR = PREFIX + "/simulator";

    private WebSocketTopics() {
    }

    /** New and updated alerts of one team. Role 2. */
    public static String alerts(long teamId) {
        return PREFIX + "/alerts/" + teamId;
    }
}
