package com.demo.upimesh;

import com.demo.upimesh.service.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JWT authentication tests:
 *
 * 1. issueToken + validateToken: claims round-trip (sub, role, exp)
 * 2. Invalid token rejected:     isTokenValid("garbage") == false
 * 3. /api/bridge/register:       returns a valid JWT in JSON response
 * 4. /api/bridge/ingest no auth: returns 401 with hint message
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthTest {

    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mockMvc;

    @Test
    void tokenRoundTrip_claimsAreCorrect() {
        String token = jwtService.issueToken("bridge-node-xyz");

        assertTrue(jwtService.isTokenValid(token), "Freshly issued token should be valid");

        Claims claims = jwtService.validateToken(token);
        assertEquals("bridge-node-xyz", claims.getSubject(), "Subject should match deviceId");
        assertEquals("BRIDGE_NODE",     claims.get("role", String.class), "Role should be BRIDGE_NODE");
        assertNotNull(claims.getIssuedAt(),  "iat should be present");
        assertNotNull(claims.getExpiration(), "exp should be present");
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()), "exp must be after iat");
    }

    @Test
    void invalidToken_isRejected() {
        assertFalse(jwtService.isTokenValid("this.is.garbage"),       "Garbage token must be invalid");
        assertFalse(jwtService.isTokenValid(""),                       "Empty string must be invalid");
        assertNull(jwtService.extractDeviceId("malformed"),           "extractDeviceId returns null on bad token");
    }

    @Test
    void registerEndpoint_returnsValidJwt() throws Exception {
        mockMvc.perform(post("/api/bridge/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"integration-test-bridge\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value("24h"))
                .andExpect(jsonPath("$.deviceId").value("integration-test-bridge"));
    }

    @Test
    void ingestWithoutJwt_returns401() throws Exception {
        String validPacket = """
                {
                  "packetId": "test-packet-001",
                  "ttl": 3,
                  "createdAt": %d,
                  "ciphertext": "dGVzdA=="
                }
                """.formatted(System.currentTimeMillis());

        mockMvc.perform(post("/api/bridge/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPacket))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
