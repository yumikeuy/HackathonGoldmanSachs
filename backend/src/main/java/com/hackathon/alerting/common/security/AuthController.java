package com.hackathon.alerting.common.security;

import com.hackathon.alerting.common.web.TooManyRequestsException;
import com.hackathon.alerting.common.web.UnauthorizedException;
import com.hackathon.alerting.delivery.model.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Login API ({@code /api/auth}, docs/security.md). Available whether or not login is enforced, so the UI and scripts
 * can always log in; with {@code alerting.security.jwt.enabled=false} the tokens are simply not required.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Lifetime of a link from {@code GET /api/auth/runbook-links/{id}} (opened right away by the UI). */
    static final Duration UI_LINK_TTL = Duration.ofMinutes(5);

    private final AuthUsers users;
    private final TokenService tokens;
    private final LoginRateLimiter loginLimiter;
    private final RunbookLinkSigner links;

    public AuthController(AuthUsers users, TokenService tokens, LoginRateLimiter loginLimiter, RunbookLinkSigner links) {
        this.users = users;
        this.tokens = tokens;
        this.loginLimiter = loginLimiter;
        this.links = links;
    }

    /** Email + password → access and refresh token. 401 on wrong credentials, 429 after 10 attempts a minute. */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        if (!loginLimiter.tryAcquire(request.email())) {
            throw new TooManyRequestsException("Too many login attempts, try again in a minute", LoginRateLimiter.WINDOW);
        }
        AuthPrincipal principal = users.authenticate(request.email(), request.password())
                .orElseThrow(() -> new UnauthorizedException("Wrong email or password"));
        return TokenResponse.from(tokens.issue(principal));
    }

    /** Refresh token → a new pair; the old refresh token stops working (rotation). */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(tokens.refresh(request.refreshToken()));
    }

    /** Revokes the refresh token; the access token simply expires. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        tokens.revoke(request.refreshToken());
    }

    /** The logged-in user, from the access token. 401 without one. */
    @GetMapping("/me")
    public CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new UnauthorizedException("Not logged in");
        }
        Object teamId = jwt.getClaims().get("teamId");
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("name"),
                Role.valueOf(jwt.getClaimAsString("role")), teamId instanceof Number number ? number.longValue() : null);
    }

    /** A short-lived signed link to a runbook file, for opening it in a new browser tab without the token. */
    @GetMapping("/runbook-links/{runbookId}")
    public RunbookLinkSigner.SignedLink runbookLink(@PathVariable long runbookId) {
        return links.sign(runbookId, UI_LINK_TTL);
    }

    /** {@code LoginRequest} of openapi.yaml. */
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** {@code RefreshRequest} of openapi.yaml. */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** {@code CurrentUser} of openapi.yaml. */
    public record CurrentUser(String subject, String email, String name, Role role, Long teamId) {
    }

    /** {@code TokenResponse} of openapi.yaml. */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                CurrentUser user) {

        static TokenResponse from(TokenService.Tokens tokens) {
            AuthPrincipal p = tokens.principal();
            return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.expiresIn(),
                    new CurrentUser(p.key(), p.email(), p.name(), p.role(), p.teamId()));
        }
    }
}
