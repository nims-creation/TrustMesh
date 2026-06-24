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
 * Content-Security-Policy
 *   Restricts which scripts/styles the browser will execute. Protects against
 *   XSS by whitelisting trusted origins. Google Fonts and cdn.jsdelivr.net
 *   (SockJS/STOMP) are required by the dashboard.
 *   data: is allowed in img-src for SVG data URIs (sparkline icons).
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
 * Referrer-Policy: strict-origin-when-cross-origin
 *   Sends the full referrer to same-origin, only the origin to cross-origin.
 *   Better than "no-referrer" for analytics while still protecting sensitive params.
 *
 * Permissions-Policy
 *   Disables browser APIs that a payment dashboard has zero business need for.
 *   Prevents malicious scripts (if injected despite CSP) from accessing camera,
 *   microphone, or geolocation.
 *
 * Cache-Control: no-store (for API endpoints)
 *   Prevents sensitive API responses from being cached by the browser or a proxy.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP =
        "default-src 'self'; " +
        // Scripts: self only + CDN for SockJS/STOMP + inline for dashboard.html logic
        "script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; " +
        // Styles: self + Google Fonts CDN
        "style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; " +
        // Fonts: Google Fonts
        "font-src 'self' https://fonts.gstatic.com; " +
        // Images: self + inline data URIs for SVG sparklines
        "img-src 'self' data:; " +
        // WebSocket connections (SockJS fallback uses XHR, STOMP uses WS)
        "connect-src 'self' ws: wss:; " +
        // Block all plugins (Flash, etc.)
        "object-src 'none'; " +
        // Block embedded iframes
        "frame-ancestors 'none'; " +
        // Force all resources to HTTPS when served over HTTPS
        "upgrade-insecure-requests;";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ── Core security headers ──────────────────────────────────────────
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // ── Permissions: disable browser APIs not needed by a payment dashboard
        response.setHeader("Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), " +
            "accelerometer=(), gyroscope=(), magnetometer=()");

        // ── API endpoints: prevent caching of sensitive payment data ───────
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        }

        filterChain.doFilter(request, response);
    }
}
