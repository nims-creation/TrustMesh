package com.demo.upimesh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds defensive HTTP security headers to every response.
 *
 * These headers are the first line of defence against common web attacks.
 * They are set here (a servlet filter) instead of in Spring Security because
 * the demo doesn't include spring-boot-starter-security, so we implement
 * them manually.
 *
 * Headers applied:
 *
 * X-Content-Type-Options: nosniff
 *   Prevents MIME-type sniffing. Without this, a browser could execute a
 *   JSON response as a script if the attacker tricks it to.
 *
 * X-Frame-Options: DENY
 *   Prevents the dashboard being embedded in an iframe (clickjacking protection).
 *
 * X-XSS-Protection: 0
 *   The value "0" is correct for modern browsers — the old "1; mode=block"
 *   actually introduced XSS vulnerabilities in some browsers. Setting it to
 *   0 tells browsers to rely on CSP instead.
 *
 * Strict-Transport-Security (HSTS)
 *   Tells the browser: always use HTTPS for this domain for the next 1 year.
 *   Safe to include even in dev — has no effect over HTTP.
 *
 * Referrer-Policy: no-referrer
 *   Prevents the URL (which may contain sensitive params) being sent as a
 *   Referer header to third-party resources.
 *
 * Cache-Control: no-store (for API endpoints)
 *   Prevents sensitive API responses from being cached by the browser or a
 *   proxy.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.setHeader("Referrer-Policy", "no-referrer");

        // For API endpoints, prevent caching of sensitive payment data
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }

        filterChain.doFilter(request, response);
    }
}
