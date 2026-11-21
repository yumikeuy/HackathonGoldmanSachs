package com.hackathon.alerting.rules.unrouted;

/**
 * A team an UNROUTED alert probably belongs to ({@code TeamSuggestion} in {@code frontend/openapi.yaml}).
 *
 * @param score  confidence 0..1, 1 = best
 * @param reason human-readable explanation shown next to the suggestion
 */
public record TeamSuggestion(long teamId, String teamName, double score, String reason) {
}
