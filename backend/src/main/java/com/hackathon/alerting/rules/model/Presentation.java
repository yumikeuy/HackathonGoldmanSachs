package com.hackathon.alerting.rules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How a rule renders its alerts ({@code rules.presentation}):
 * {@code {"title":"Rejected {{trade.id}} ({{trade.notional}})"}}.
 * {@code {{path}}} placeholders are substituted from the event when the alert is created.
 *
 * @param title alert title template; {@code null} means a default title (e.g. the rule name)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Presentation(String title) {

    /** No customisation: {@code {}} in the database. */
    public static final Presentation DEFAULT = new Presentation(null);
}
