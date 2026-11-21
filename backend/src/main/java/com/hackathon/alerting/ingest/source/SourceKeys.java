package com.hackathon.alerting.ingest.source;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys of HTTP sources (docs/security.md). A source may require a key: the sender passes it in
 * {@value #HEADER}. Only the SHA-256 of the key is stored, in {@code sources.config.apiKeySha256} (no schema change);
 * the key itself is shown once, when it is generated. Sources without a key accept events as before.
 */
public final class SourceKeys {

    /** Request header carrying the key. */
    public static final String HEADER = "X-Api-Key";
    /** Field of {@code sources.config} holding the key hash; never returned by the API. */
    public static final String HASH_FIELD = "apiKeySha256";
    static final String PREFIX = "src_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SourceKeys() {
    }

    /** A new random key: {@code src_} + 32 random bytes, base64url. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Whether the source config requires a key. */
    public static boolean isRequired(JsonNode config) {
        JsonNode hash = config == null ? null : config.get(HASH_FIELD);
        return hash != null && hash.isString() && !hash.asString().isEmpty();
    }

    /**
     * Whether {@code presented} is the source's key. Constant-time comparison of the hashes; a source without a key
     * accepts anything.
     */
    public static boolean matches(JsonNode config, String presented) {
        if (!isRequired(config)) {
            return true;
        }
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        byte[] expected = config.get(HASH_FIELD).asString().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(presented).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** A copy of the config without the key hash, for API responses. */
    public static JsonNode withoutSecrets(JsonNode config) {
        if (config instanceof ObjectNode object && object.has(HASH_FIELD)) {
            ObjectNode copy = object.deepCopy();
            copy.remove(HASH_FIELD);
            return copy;
        }
        return config;
    }
}
