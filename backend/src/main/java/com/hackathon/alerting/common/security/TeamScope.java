package com.hackathon.alerting.common.security;

import com.hackathon.alerting.delivery.model.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Objects;
import java.util.Optional;

/**
 * What the current request may see (docs/security.md, "Team isolation"). An administrator — or anyone while login is
 * not enforced — sees everything; a VIEWER or OPERATOR sees only their own team: its alerts, rules, runbooks (plus
 * global ones), users, and nothing unrouted.
 *
 * @param role   role from the access token
 * @param teamId the user's team ({@code null} for the built-in administrator)
 */
public record TeamScope(Role role, Long teamId) {

    /** The scope of the authenticated caller, or empty when nobody is logged in (login not enforced). */
    public static Optional<TeamScope> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        String role = token.getToken().getClaimAsString("role");
        Object team = token.getToken().getClaims().get("teamId");
        return Optional.of(new TeamScope(role == null ? Role.VIEWER : Role.valueOf(role),
                team instanceof Number n ? n.longValue() : null));
    }

    /** Whether the caller is limited to one team (logged in and not an administrator). */
    public static boolean restricted() {
        return current().map(scope -> scope.role() != Role.ADMIN).orElse(false);
    }

    /** Whether a restricted caller may see data of {@code teamId}; {@code null} (unrouted) is never theirs. */
    public boolean owns(Long teamId) {
        return role == Role.ADMIN || (teamId != null && Objects.equals(teamId, this.teamId));
    }
}
