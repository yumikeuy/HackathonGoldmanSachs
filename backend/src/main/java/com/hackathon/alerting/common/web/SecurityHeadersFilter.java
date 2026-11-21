package com.hackathon.alerting.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Defensive HTTP headers on every response (docs/security.md):
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — the browser never guesses a type (no "this .txt is really HTML");</li>
 *   <li>{@code X-Frame-Options: DENY} and CSP {@code frame-ancestors 'none'} — no clickjacking;</li>
 *   <li>{@code Referrer-Policy: no-referrer};</li>
 *   <li>on {@code /api/**} a strict CSP {@code default-src 'none'}: API responses are data, never a page that runs
 *       scripts. The UI and Swagger pages keep working because they are not under {@code /api}.</li>
 * </ul>
 * Runbook downloads get a stricter policy on top, see {@link SafeFileDownloadAdvice}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    static final String PAGE_CSP = "frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        boolean api = request.getRequestURI().startsWith("/api/");
        response.setHeader("Content-Security-Policy", api ? API_CSP : PAGE_CSP);
        chain.doFilter(request, response);
    }
}
