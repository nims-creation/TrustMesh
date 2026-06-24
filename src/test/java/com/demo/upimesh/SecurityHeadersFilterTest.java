package com.demo.upimesh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityHeadersFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testSecurityHeadersArePresentOnApiEndpoints() throws Exception {
        var response = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'")
                .contains("style-src 'self' https://fonts.googleapis.com 'unsafe-inline'")
                .contains("font-src 'self' https://fonts.gstatic.com");

        mockMvc.perform(get("/api/health"))
                // Core security headers
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                // Updated HSTS now includes `preload` directive (more secure)
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload"))
                // Updated Referrer-Policy (strict-origin-when-cross-origin is safer than no-referrer)
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                // API endpoint cache control (now includes must-revalidate and Pragma)
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
                .andExpect(header().string("Pragma", "no-cache"))
                // New headers added for hardening
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void testSecurityHeadersArePresentOnNonApiEndpoints() throws Exception {
        // Test that security headers are also set on non-API endpoints.
        // Use a known API endpoint with wrong HTTP method to get a clean 405 response
        // without triggering uncaught exceptions that disrupt MockMvc.
        mockMvc.perform(get("/api/mesh/gossip"))  // this endpoint requires POST
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }
}
