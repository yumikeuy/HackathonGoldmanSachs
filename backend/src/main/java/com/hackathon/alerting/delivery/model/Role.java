package com.hackathon.alerting.delivery.model;

/**
 * What a logged-in user may do ({@code users.role}, the {@code role} claim of the access token). Each role includes
 * the ones below it (docs/security.md).
 */
public enum Role {

    /** Read everything: alerts, rules, teams, runbooks, simulator. */
    VIEWER,
    /** Plus work alerts: ack, resolve, replay failed events. */
    OPERATOR,
    /** Plus change configuration: sources, teams, users, rules, runbooks, simulator switches, generator. */
    ADMIN
}
