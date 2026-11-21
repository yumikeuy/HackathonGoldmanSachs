package com.hackathon.alerting.common.json;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Contract (owner Role 3): find a field of an arbitrary event by its dotted path (AGENTS.md §5).
 *
 * <ul>
 *   <li>{@code strict = true}: exact path only ({@code trade.notional} → {@code event.trade.notional});</li>
 *   <li>{@code strict = false}: exact path first; if absent, the first key anywhere in the event whose name
 *       equals the last path segment ({@code notional}), case-insensitive.</li>
 * </ul>
 * Used by conditions, grouping ({@code group_by_paths}), and title templates.
 */
public interface JsonFieldLocator {

    /**
     * @param event  the raw event
     * @param path   dotted path, e.g. {@code trade.notional}
     * @param strict whether only the exact path is allowed
     * @return the value node; empty if the field is missing or JSON {@code null}
     */
    Optional<JsonNode> find(JsonNode event, String path, boolean strict);
}
