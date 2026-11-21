package com.hackathon.alerting.common.security;

import com.hackathon.alerting.common.web.ServiceUnavailableException;
import com.hackathon.alerting.common.web.UnauthorizedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues and rotates tokens (docs/security.md).
 *
 * <ul>
 *   <li><b>Access token</b> — a JWT (HS256), lifetime {@code access-ttl} (15 min), claims {@code sub} (principal key),
 *       {@code role}, {@code email}, {@code name}, {@code teamId}. Checked without any database or Redis access.</li>
 *   <li><b>Refresh token</b> — 32 random bytes, lifetime {@code refresh-ttl} (7 days). Redis keeps only its SHA-256:
 *       {@code auth:refresh:{sha256} → principal key}. Every refresh <b>rotates</b> it: the old one is deleted
 *       atomically ({@code GETDEL}), so a stolen refresh token works at most once, and logout revokes it.</li>
 * </ul>
 */
@Service
public class TokenService {

    static final String REFRESH_PREFIX = "auth:refresh:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final StringRedisTemplate redis;
    private final AuthUsers users;
    private final AuthSettings settings;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, StringRedisTemplate redis, AuthUsers users, AuthSettings settings, Clock clock) {
        this.encoder = encoder;
        this.redis = redis;
        this.users = users;
        this.settings = settings;
        this.clock = clock;
    }

    /** A new access + refresh pair for a principal that just proved its identity. */
    public Tokens issue(AuthPrincipal principal) {
        String refresh = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        try {
            redis.opsForValue().set(REFRESH_PREFIX + sha256(refresh), principal.key(), settings.refreshTtl());
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("Session store (Redis) is unavailable, retry later", e);
        }
        return new Tokens(accessToken(principal), refresh, settings.accessTtl().toSeconds(), principal);
    }

    /**
     * Exchanges a refresh token for a new pair; the presented one stops working.
     *
     * @throws UnauthorizedException if it is unknown, expired, already used or the user may no longer log in
     */
    public Tokens refresh(String refreshToken) {
        String key = consume(refreshToken);
        AuthPrincipal principal = users.byKey(key)
                .orElseThrow(() -> new UnauthorizedException("The user can no longer log in"));
        return issue(principal);
    }

    /** Revokes a refresh token; unknown tokens are ignored. */
    public void revoke(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                redis.delete(REFRESH_PREFIX + sha256(refreshToken));
            } catch (RuntimeException e) {
                throw new ServiceUnavailableException("Session store (Redis) is unavailable, retry later", e);
            }
        }
    }

    private String consume(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Refresh token is missing");
        }
        String key;
        try {
            key = redis.opsForValue().getAndDelete(REFRESH_PREFIX + sha256(refreshToken));
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("Session store (Redis) is unavailable, retry later", e);
        }
        if (key == null) {
            throw new UnauthorizedException("Refresh token is invalid, expired or already used — log in again");
        }
        return key;
    }

    private String accessToken(AuthPrincipal principal) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(principal.key())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(settings.accessTtl()))
                .claim("role", principal.role().name())
                .claim("email", principal.email())
                .claim("name", principal.name());
        if (principal.teamId() != null) {
            claims.claim("teamId", principal.teamId());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token pair. {@code expiresIn} is the access token lifetime in seconds. */
    public record Tokens(String accessToken, String refreshToken, long expiresIn, AuthPrincipal principal) {
    }
}
