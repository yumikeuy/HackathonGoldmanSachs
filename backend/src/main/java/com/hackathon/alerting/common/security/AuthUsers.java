package com.hackathon.alerting.common.security;

import com.hackathon.alerting.delivery.model.Role;
import com.hackathon.alerting.delivery.model.User;
import com.hackathon.alerting.delivery.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Checks credentials and resolves token subjects. Users log in with their email and the password an administrator
 * set ({@code users.password_hash}, BCrypt); inactive users and users without a password cannot log in. The built-in
 * administrator ({@code alerting.security.admin.*}) exists so the very first login is possible on an empty database.
 */
@Component
public class AuthUsers {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthSettings settings;
    private final String adminPasswordHash;
    /** Compared against when the email is unknown, so timing does not reveal which emails exist. */
    private final String dummyHash;

    public AuthUsers(UserRepository users, PasswordEncoder passwords, AuthSettings settings) {
        this.users = users;
        this.passwords = passwords;
        this.settings = settings;
        this.adminPasswordHash = passwords.encode(settings.adminPassword());
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    /** The principal for valid credentials, otherwise empty. */
    @Transactional(readOnly = true)
    public Optional<AuthPrincipal> authenticate(String email, String password) {
        if (email == null || password == null) {
            return Optional.empty();
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals(settings.adminEmail().toLowerCase(Locale.ROOT))) {
            return passwords.matches(password, adminPasswordHash) ? Optional.of(admin()) : Optional.empty();
        }
        Optional<User> user = users.findByEmail(email.trim());
        if (user.isEmpty() || user.get().getPasswordHash() == null || !user.get().isActive()) {
            passwords.matches(password, dummyHash);
            return Optional.empty();
        }
        return passwords.matches(password, user.get().getPasswordHash()) ? Optional.of(toPrincipal(user.get())) : Optional.empty();
    }

    /** Current state of a token subject — a refresh must not resurrect a deactivated user or a lowered role. */
    @Transactional(readOnly = true)
    public Optional<AuthPrincipal> byKey(String key) {
        if (AuthPrincipal.ADMIN_KEY.equals(key)) {
            return Optional.of(admin());
        }
        if (key == null || !key.startsWith("user:")) {
            return Optional.empty();
        }
        try {
            return users.findById(Long.parseLong(key.substring("user:".length())))
                    .filter(User::isActive)
                    .filter(user -> user.getPasswordHash() != null)
                    .map(AuthUsers::toPrincipal);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private AuthPrincipal admin() {
        return new AuthPrincipal(AuthPrincipal.ADMIN_KEY, null, settings.adminEmail(), "Administrator", Role.ADMIN, null);
    }

    private static AuthPrincipal toPrincipal(User user) {
        return new AuthPrincipal(AuthPrincipal.userKey(user.getId()), user.getId(), user.getEmail(), user.getName(),
                user.getRole(), user.getTeam().getId());
    }
}
