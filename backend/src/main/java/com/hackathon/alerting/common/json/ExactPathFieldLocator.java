package com.hackathon.alerting.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * STEP-0 STUB of {@link JsonFieldLocator} (owner Role 3): exact dotted path only, {@code strict} is ignored.
 * Role 3 replaces the body with the flexible lookup (key search, case-insensitive) described in the contract.
 */
@Component
public class ExactPathFieldLocator implements JsonFieldLocator {

    @Override
    public Optional<JsonNode> find(JsonNode event, String path, boolean strict) {
        JsonNode current = event;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return Optional.empty();
            }
            current = current.get(segment);
        }
        return current == null || current.isNull() ? Optional.empty() : Optional.of(current);
    }
}
