package com.hackathon.alerting.rules.unrouted;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Flattens a condition tree (AGENTS.md §5) into its leaves — the individual field checks. */
final class ConditionLeaves {

    private ConditionLeaves() {
    }

    /**
     * @param condition a condition tree, or {@code null}
     * @return every leaf ({@code {"path","op","value"}}) in document order; empty for {@code null}
     */
    static List<JsonNode> of(JsonNode condition) {
        List<JsonNode> leaves = new ArrayList<>();
        collect(condition, leaves);
        return leaves;
    }

    private static void collect(JsonNode node, List<JsonNode> leaves) {
        if (node == null || !node.isObject()) {
            return;
        }
        boolean branch = false;
        for (String combinator : new String[]{"and", "or", "not"}) {
            JsonNode children = node.get(combinator);
            if (children == null) {
                continue;
            }
            branch = true;
            if (children.isArray()) {
                children.values().forEach(child -> collect(child, leaves));
            } else {
                collect(children, leaves);
            }
        }
        if (!branch && node.has("path")) {
            leaves.add(node);
        }
    }
}
