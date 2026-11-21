package com.hackathon.alerting.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Settings of {@code alerting.security.jwt.*} and the built-in administrator. The defaults are for local development
 * only — a warning is logged when login is enabled with the default secret or admin password.
 */
@Component
public class AuthSettings {

    static final String DEFAULT_SECRET = "dev-only-secret-change-me-in-production-0123456789";
    static final String DEFAULT_ADMIN_PASSWORD = "admin";

    private static final Logger log = LoggerFactory.getLogger(AuthSettings.class);

    private final boolean enabled;
    private final byte[] secret;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final String adminEmail;
    private final String adminPassword;

    public AuthSettings(@Value("${alerting.security.jwt.enabled:false}") boolean enabled,
                        @Value("${alerting.security.jwt.secret:" + DEFAULT_SECRET + "}") String secret,
                        @Value("${alerting.security.jwt.access-ttl:15m}") Duration accessTtl,
                        @Value("${alerting.security.jwt.refresh-ttl:7d}") Duration refreshTtl,
                        @Value("${alerting.security.admin.email:admin@alerting.local}") String adminEmail,
                        @Value("${alerting.security.admin.password:" + DEFAULT_ADMIN_PASSWORD + "}") String adminPassword) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("alerting.security.jwt.secret must be at least 32 bytes (HS256)");
        }
        this.enabled = enabled;
        this.secret = bytes;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        if (enabled && DEFAULT_SECRET.equals(secret)) {
            log.warn("Login is enabled with the default JWT secret — set JWT_SECRET");
        }
        if (enabled && DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            log.warn("Login is enabled with the default admin password — set ADMIN_PASSWORD");
        }
    }

    /** Whether requests are authenticated and authorized. */
    public boolean enabled() {
        return enabled;
    }

    byte[] secret() {
        return secret.clone();
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    String adminEmail() {
        return adminEmail;
    }

    String adminPassword() {
        return adminPassword;
    }
}
