package com.hackathon.alerting.rules.dedup;

import com.hackathon.alerting.common.json.JsonFieldLocator;
import com.hackathon.alerting.rules.cache.RuleRoute;
import com.hackathon.alerting.rules.model.GroupMode;
import com.hackathon.alerting.rules.model.Rule;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * Computes alert fingerprints (AGENTS.md §6, steps 6 and 12): SHA-256 in lowercase hex, 64 characters.
 *
 * <ul>
 *   <li>rule alert: {@code sha256(rule id + values of group_by_paths [+ window number for per_window])};</li>
 *   <li>unrouted alert: {@code sha256("unrouted:" + source name + sorted top-level keys)}.</li>
 * </ul>
 * Parts are joined with the unit separator U+001F, and a missing value is encoded differently from JSON
 * {@code null} or an empty string, so different inputs never collide by concatenation.
 */
@Component
public class FingerprintCalculator {

    private static final char SEPARATOR = '\u001f';
    private static final String MISSING = "\u0000missing";

    private final JsonFieldLocator fields;

    public FingerprintCalculator(JsonFieldLocator fields) {
        this.fields = fields;
    }

    /**
     * @param route      the matched rule
     * @param event      the raw event
     * @param ingestedAt event time; selects the window for {@link GroupMode#PER_WINDOW}
     */
    public String forRule(RuleRoute route, JsonNode event, Instant ingestedAt) {
        Rule rule = route.rule();
        StringBuilder key = new StringBuilder("rule:").append(rule.getId());
        for (String path : rule.getGroupByPaths()) {
            key.append(SEPARATOR).append(path).append('=')
                    .append(fields.find(event, path, route.strict()).map(JsonNode::toString).orElse(MISSING));
        }
        if (rule.getGroupMode() == GroupMode.PER_WINDOW) {
            key.append(SEPARATOR).append("window=").append(windowNumber(ingestedAt, rule.getDedupWindowSec()));
        }
        return sha256(key.toString());
    }

    /** Fingerprint of an event no team matched: same source and same top-level shape → same alert. */
    public String forUnrouted(String sourceName, JsonNode event) {
        StringBuilder key = new StringBuilder("unrouted:").append(sourceName);
        TreeSet<String> topLevelKeys = new TreeSet<>();
        if (event.isObject()) {
            Iterator<String> names = event.propertyNames().iterator();
            names.forEachRemaining(topLevelKeys::add);
        }
        topLevelKeys.forEach(name -> key.append(SEPARATOR).append(name));
        return sha256(key.toString());
    }

    /** Index of the fixed-size window containing {@code time}: windows are aligned to the epoch. */
    static long windowNumber(Instant time, int windowSec) {
        return Math.floorDiv(time.getEpochSecond(), windowSec);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
