package com.hackathon.alerting.common.web;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Opaque, URL-safe cursors for keyset pagination over Spring Data {@link Window} queries.
 *
 * <p>A cursor is base64url JSON of the last row's sort keys, each tagged with its type so it
 * decodes back to the exact Java type the query compares against
 * ({@code {"lastSeenAt":"t:2026-11-21T00:17:03Z","id":"l:42"}}). Clients must treat it as opaque.
 *
 * <pre>{@code
 * Window<Alert> window = alerts.findByTeamIdOrderByLastSeenAtDescIdAsc(teamId, Cursors.decode(cursor), Cursors.limit(limit));
 * return CursorPage.of(window, AlertDto::from);
 * }</pre>
 */
public final class Cursors {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursors() {
    }

    /**
     * Scroll position for a request cursor.
     *
     * @param cursor value of {@code ?cursor=}; {@code null} or blank means the first page
     * @throws InvalidRequestException if the cursor is malformed
     */
    public static ScrollPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return ScrollPosition.keyset();
        }
        try {
            JsonNode node = JSON.readTree(DECODER.decode(cursor));
            if (!(node instanceof ObjectNode object) || object.isEmpty()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            Map<String, Object> keys = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                keys.put(entry.getKey(), decodeValue(entry.getValue().asString()));
            }
            return ScrollPosition.forward(keys);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new InvalidRequestException("Invalid cursor", e);
        }
    }

    /** Cursor for the page after {@code window}, or {@code null} if it is the last page. */
    public static String next(Window<?> window) {
        if (!window.hasNext() || window.isEmpty()) {
            return null;
        }
        if (!(window.positionAt(window.size() - 1) instanceof KeysetScrollPosition keyset)) {
            throw new IllegalStateException("Cursors support keyset windows only");
        }
        ObjectNode object = JSON.createObjectNode();
        keyset.getKeys().forEach((name, value) -> object.put(name, encodeValue(name, value)));
        return ENCODER.encodeToString(JSON.writeValueAsBytes(object));
    }

    /**
     * Page size for a request {@code ?limit=}: {@code null} → {@link CursorPage#DEFAULT_LIMIT},
     * otherwise clamped to {@code 1..}{@link CursorPage#MAX_LIMIT}.
     */
    public static Limit limit(Integer requested) {
        int size = requested == null ? CursorPage.DEFAULT_LIMIT : Math.clamp(requested, 1, CursorPage.MAX_LIMIT);
        return Limit.of(size);
    }

    private static String encodeValue(String name, Object value) {
        return switch (value) {
            case Instant instant -> "t:" + instant;
            case Long number -> "l:" + number;
            case Integer number -> "i:" + number;
            case UUID uuid -> "u:" + uuid;
            case String text -> "s:" + text;
            case null -> throw new IllegalStateException("Sort key '" + name + "' is null; keyset sort keys must be NOT NULL");
            default -> throw new IllegalStateException("Unsupported sort key type for '" + name + "': " + value.getClass());
        };
    }

    private static Object decodeValue(String tagged) {
        if (tagged == null || tagged.length() < 2 || tagged.charAt(1) != ':') {
            throw new IllegalArgumentException("malformed key");
        }
        String raw = tagged.substring(2);
        return switch (tagged.charAt(0)) {
            case 't' -> Instant.parse(raw);
            case 'l' -> Long.valueOf(raw);
            case 'i' -> Integer.valueOf(raw);
            case 'u' -> UUID.fromString(raw);
            case 's' -> raw;
            default -> throw new IllegalArgumentException("unknown key type");
        };
    }
}
