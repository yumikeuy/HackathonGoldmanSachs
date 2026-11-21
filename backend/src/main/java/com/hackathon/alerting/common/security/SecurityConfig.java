package com.hackathon.alerting.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.util.List;

/**
 * Who may call what (docs/security.md). With {@code alerting.security.jwt.enabled=false} (default) every request is
 * allowed, exactly as before login existed. With it enabled, {@code /api} requires an access token
 * ({@code Authorization: Bearer …}):
 *
 * <table>
 *   <tr><th>Open</th><td>{@code POST /api/auth/login|refresh}, {@code POST /api/ingest/*} (source API keys instead),
 *       runbook files with a valid signed link, UI pages, Swagger, {@code /ws} handshake (STOMP CONNECT is checked
 *       by {@link StompAuthInterceptor}), actuator health/prometheus</td></tr>
 *   <tr><th>VIEWER</th><td>every {@code GET /api/**}, {@code POST /api/rules/dry-run}, logout</td></tr>
 *   <tr><th>OPERATOR</th><td>ack, resolve, replay of failed events; create, edit and delete runbooks of their own team
 *       ({@link TeamScopeInterceptor} enforces the team; global runbooks stay with administrators)</td></tr>
 *   <tr><th>ADMIN</th><td>every other change: sources, teams, users, rules, global runbooks, simulator, generator</td></tr>
 * </table>
 * Stateless: no session, no cookies, hence no CSRF tokens. Errors are RFC 9457 problem+json (401 / 403).
 *
 * <p>Boot's security auto-configuration is excluded in {@code application.yml}; this class is the only security setup.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthSettings settings, RunbookLinkSigner links) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)           // bearer tokens only, nothing rides on cookies
                .cors(Customizer.withDefaults())
                .headers(AbstractHttpConfigurer::disable)     // SecurityHeadersFilter owns the response headers
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults());

        if (!settings.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        AuthenticationEntryPoint unauthorized = (request, response, e) -> problem(response, HttpStatus.UNAUTHORIZED,
                "Log in: send 'Authorization: Bearer <access token>' (POST /api/auth/login)");
        AccessDeniedHandler forbidden = (request, response, e) -> problem(response, HttpStatus.FORBIDDEN,
                "Your role does not allow " + request.getMethod() + " " + request.getRequestURI());
        RequestMatcher signedRunbookFile = request -> HttpMethod.GET.matches(request.getMethod())
                && links.isValid(request.getRequestURI(), request.getParameter("exp"), request.getParameter("sig"));

        http.authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/ingest/*").permitAll()
                        .requestMatchers(signedRunbookFile).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole("VIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/rules/dry-run", "/api/auth/logout").hasRole("VIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/alerts/*/ack", "/api/alerts/*/resolve", "/api/events/*/replay")
                        .hasRole("OPERATOR")
                        // runbooks of the operator's own team — TeamScopeInterceptor checks the team
                        .requestMatchers(HttpMethod.POST, "/api/runbooks").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/runbooks/*").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/runbooks/*").hasRole("OPERATOR")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new RoleClaimConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    /** The {@code role} claim becomes the single authority {@code ROLE_<role>}; the hierarchy adds the lower roles. */
    static final class RoleClaimConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            String role = jwt.getClaimAsString("role");
            List<SimpleGrantedAuthority> authorities = role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        }
    }

    private static void problem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        response.getWriter().write(body);
    }
}
