package com.hackathon.alerting.common.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signed, expiring links to runbook files: {@code /api/runbooks/{id}/file?exp={epochSeconds}&sig={hmac}}.
 * Notifications (email, SMS) and the UI's "open in a new tab" use them, because a plain link carries no
 * {@code Authorization} header; with login enabled a file is served to a bearer token <b>or</b> a valid signature.
 * HMAC-SHA256 over {@code runbook:{id}:{exp}} with a key derived from the JWT secret; only this one file, only until
 * {@code exp}.
 */
@Component
public class RunbookLinkSigner {

    private static final Pattern FILE_PATH = Pattern.compile("^/api/runbooks/(\\d+)/file$");

    private final byte[] key;
    private final Clock clock;

    public RunbookLinkSigner(AuthSettings settings, Clock clock) {
        this.key = hmac(settings.secret(), "runbook-links".getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** Path with query: {@code /api/runbooks/{id}/file?exp=…&sig=…}, valid for {@code ttl}. */
    public SignedLink sign(long runbookId, Duration ttl) {
        long exp = clock.instant().plus(ttl).getEpochSecond();
        String path = "/api/runbooks/" + runbookId + "/file?exp=" + exp + "&sig=" + signature(runbookId, exp);
        return new SignedLink(path, Instant.ofEpochSecond(exp));
    }

    /** Whether a request for {@code path} carries a valid, unexpired signature. */
    public boolean isValid(String path, String exp, String sig) {
        Matcher matcher = FILE_PATH.matcher(path == null ? "" : path);
        if (!matcher.matches() || exp == null || sig == null) {
            return false;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(exp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expiresAt) {
            return false;
        }
        String expected = signature(Long.parseLong(matcher.group(1)), expiresAt);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(long runbookId, long exp) {
        byte[] mac = hmac(key, ("runbook:" + runbookId + ":" + exp).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A signed path and when it stops working. */
    public record SignedLink(String url, Instant expiresAt) {
    }
}
