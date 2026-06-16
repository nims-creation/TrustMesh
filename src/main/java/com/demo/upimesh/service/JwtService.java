package com.demo.upimesh.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT service for bridge node authentication.
 *
 * Flow:
 *   1. Bridge node calls POST /api/bridge/register with its deviceId
 *   2. Server issues a signed JWT (HS256, 24h validity)
 *   3. Bridge includes token in: Authorization: Bearer <token>
 *   4. JwtAuthFilter validates the token on every /api/bridge/ingest call
 *
 * Why JWT over API keys?
 *   - JWT carries claims (deviceId, issuedAt, expiry) — server is stateless
 *   - API keys require a database lookup per request — JWT verifies with HMAC alone
 *   - Built-in expiry (UPI offline window is 24h — token matches)
 *   - Interviewers recognise this pattern (it's how real UPI bridge registration works)
 *
 * Why HMAC-SHA256 (HS256) not RSA (RS256)?
 *   - HS256: same secret signs and verifies — simpler, faster, works for single-service
 *   - RS256: private key signs, public key verifies — required when external services
 *     need to verify tokens without the secret. Not needed here.
 *   - Production: use RS256 with keys from AWS KMS if multiple services need to verify
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) {
        // Derive a secure HMAC-SHA256 key from the configured secret string
        this.signingKey  = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        log.info("JwtService initialised (expiry={}ms, algorithm=HS256)", expirationMs);
    }

    /**
     * Issues a signed JWT for the given bridge node.
     *
     * Claims:
     *   sub  = deviceId           (subject — who this token belongs to)
     *   role = BRIDGE_NODE        (authorization role)
     *   iat  = now                (issued at)
     *   exp  = now + expirationMs (expiry — defaults to 24h)
     */
    public String issueToken(String deviceId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        String token = Jwts.builder()
                .subject(deviceId)
                .claims(Map.of("role", "BRIDGE_NODE"))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();

        log.info("JWT issued for bridge node '{}' (expires: {})", deviceId, expiry);
        return token;
    }

    /**
     * Validates a JWT and returns its claims.
     *
     * @param token the raw JWT string (without "Bearer " prefix)
     * @return parsed claims if valid
     * @throws JwtException if the token is expired, tampered, or malformed
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the bridge node deviceId (subject) from a JWT.
     * Returns null if the token is invalid rather than throwing.
     */
    public String extractDeviceId(String token) {
        try {
            return validateToken(token).getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT extraction failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns true if the token is structurally valid and not expired.
     */
    public boolean isTokenValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired for token ending in ...{}", token.length() > 10 ? token.substring(token.length() - 10) : "?");
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT invalid: {}", e.getMessage());
            return false;
        }
    }
}
