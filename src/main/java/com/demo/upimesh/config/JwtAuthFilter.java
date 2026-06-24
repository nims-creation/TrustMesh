package com.demo.upimesh.config;

import com.demo.upimesh.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * JWT authentication filter protecting /api/bridge/ingest.
 *
 * Only the production bridge endpoint requires authentication.
 * All other endpoints (dashboard, demo, mesh controls) remain open
 * so the live demo can be explored without registration.
 *
 * Flow:
 *   1. Request arrives at /api/bridge/ingest
 *   2. Filter checks for "Authorization: Bearer <token>" header
 *   3. Missing header → 401 Unauthorized (JSON body with hint)
 *   4. Invalid/expired token → 401 Unauthorized
 *   5. Valid token → sets X-Bridge-Node-Id header from JWT subject + continues
 *
 * Note: The demo dashboard bypasses this filter by using /api/mesh/flush
 * (which goes through MeshSimulatorService internally, not the raw bridge endpoint).
 * Real bridge nodes in production would call /api/bridge/ingest directly with a JWT.
 *
 * Security note: JSON error responses are built with Jackson ObjectMapper (NOT
 * string concatenation) to prevent JSON injection if error messages contain quotes.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String INGEST_PATH = "/api/bridge/ingest";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Only protect the production bridge ingest endpoint.
        // Use getRequestURI() — getServletPath() returns "" in MockMvc (Spring Boot 3)
        // which would cause the filter to silently skip the auth check in tests.
        String uri = request.getRequestURI();
        if (!INGEST_PATH.equals(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        // ── Missing header ───────────────────────────────────
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[jwt] Missing or malformed Authorization header on {}", INGEST_PATH);
            sendUnauthorized(response,
                "Missing Authorization header.",
                "POST /api/bridge/register with {\"deviceId\":\"your-device-id\"} to get a token.");
            return;
        }

        String token = authHeader.substring(7);

        // ── Validate token ───────────────────────────────────
        if (!jwtService.isTokenValid(token)) {
            log.warn("[jwt] Invalid or expired JWT on {}", INGEST_PATH);
            sendUnauthorized(response,
                "Invalid or expired JWT.",
                "Re-register at POST /api/bridge/register.");
            return;
        }

        // ── Extract claims + forward ─────────────────────────
        Claims claims    = jwtService.validateToken(token);
        String deviceId  = claims.getSubject();
        String role      = claims.get("role", String.class);

        log.debug("[jwt] Authenticated bridge node '{}' (role={})", deviceId, role);

        // Propagate deviceId so ApiController can log which bridge submitted the packet
        request.setAttribute("authenticatedBridgeNodeId", deviceId);

        filterChain.doFilter(request, response);
    }

    /**
     * Sends a structured 401 JSON response using ObjectMapper.
     *
     * SECURITY: Using ObjectMapper.writeValueAsString() instead of string concatenation
     * prevents JSON injection if error messages ever contain special characters
     * (e.g. quotes, backslashes) from attacker-controlled input.
     */
    private void sendUnauthorized(HttpServletResponse response,
                                  String message,
                                  String hint) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, String> body = Map.of(
            "error",   "UNAUTHORIZED",
            "message", message,
            "hint",    hint
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
