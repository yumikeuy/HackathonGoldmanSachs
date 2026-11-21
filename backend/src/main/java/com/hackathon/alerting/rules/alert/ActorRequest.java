package com.hackathon.alerting.rules.alert;

import jakarta.validation.constraints.Size;

/**
 * Optional body of {@code POST /api/alerts/{id}/ack|resolve}: who acts.
 *
 * @param actor user email; blank or absent means {@value #DEFAULT_ACTOR}
 */
public record ActorRequest(@Size(max = 100) String actor) {

    public static final String DEFAULT_ACTOR = "ui";

    /** The actor to audit, never blank. */
    public static String actorOf(ActorRequest request) {
        return request == null || request.actor() == null || request.actor().isBlank() ? DEFAULT_ACTOR : request.actor().strip();
    }
}
