package com.hackathon.alerting.common.security;

import com.hackathon.alerting.delivery.model.Role;

/**
 * Who is logged in: a user of {@code users}, or the built-in administrator.
 *
 * @param key    stable identity used as the token subject: {@code user:{id}} or {@code admin}
 * @param userId {@code users.id}, {@code null} for the built-in administrator
 * @param teamId the user's team, {@code null} for the built-in administrator
 */
public record AuthPrincipal(String key, Long userId, String email, String name, Role role, Long teamId) {

    static final String ADMIN_KEY = "admin";

    static String userKey(long userId) {
        return "user:" + userId;
    }
}
