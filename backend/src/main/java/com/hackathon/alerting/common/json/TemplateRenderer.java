package com.hackathon.alerting.common.json;

import tools.jackson.databind.JsonNode;

/**
 * Contract (owner Role 3): substitute {@code {{path}}} placeholders with event values,
 * e.g. {@code "Rejected {{trade.id}} ({{trade.notional}})"} → {@code "Rejected T-1 (15000000)"}.
 * Used for {@code rules.presentation.title} and notification texts.
 *
 * <p>Placeholders resolve through {@link JsonFieldLocator} in flexible mode; a missing value renders as
 * an empty string. Text values are inserted without quotes; objects/arrays as compact JSON.
 */
public interface TemplateRenderer {

    String render(String template, JsonNode event);
}
