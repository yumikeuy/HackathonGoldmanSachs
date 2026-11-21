package com.hackathon.alerting.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link JsonFieldLocator} implementation (owner: Role 3).
 *
 * <ul>
 *   <li>{@code strict = true}: exact dotted path only (object keys and numeric array indices).</li>
 *   <li>{@code strict = false}: tries exact dotted path first; if absent or JSON {@code null},
 *       falls back to breadth-first search by the last path segment (case-insensitive).</li>
 * </ul>
 */
@Component
public class DefaultJsonFieldLocator implements JsonFieldLocator {

    @Override
    public Optional<JsonNode> find(JsonNode event, String path, boolean strict) {
        if (event == null || event.isNull() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        Optional<JsonNode> exact = findExactPath(event, path);
        if (exact.isPresent() || strict) {
            return exact;
        }
        int lastDot = path.lastIndexOf('.');
        String key = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return findByKeyBfs(event, key);
    }

    private static Optional<JsonNode> findExactPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty() || current == null) {
                return Optional.empty();
            }
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                int index = parseArrayIndex(segment);
                if (index < 0 || index >= current.size()) {
                    return Optional.empty();
                }
                current = current.get(index);
            } else {
                return Optional.empty();
            }
        }
        return current == null || current.isNull() ? Optional.empty() : Optional.of(current);
    }

    private static Optional<JsonNode> findByKeyBfs(JsonNode root, String key) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            JsonNode current = queue.poll();
            if (current.isObject()) {
                for (Map.Entry<String, JsonNode> entry : current.properties()) {
                    JsonNode value = entry.getValue();
                    if (entry.getKey().equalsIgnoreCase(key) && value != null && !value.isNull()) {
                        return Optional.of(value);
                    }
                }
                for (Map.Entry<String, JsonNode> entry : current.properties()) {
                    JsonNode child = entry.getValue();
                    if (child != null && (child.isObject() || child.isArray())) {
                        queue.add(child);
                    }
                }
            } else if (current.isArray()) {
                for (JsonNode child : current.values()) {
                    if (child != null && (child.isObject() || child.isArray())) {
                        queue.add(child);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static int parseArrayIndex(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
