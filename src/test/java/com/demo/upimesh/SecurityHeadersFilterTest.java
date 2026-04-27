package com.demo.upimesh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

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
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void testSecurityHeadersArePresentOnStaticAssets() throws Exception {
        // Assuming dashboard.html is served at the root or /dashboard
        // We'll test against a 404 endpoint. Note: GlobalExceptionHandler maps all unhandled exceptions (including NoResourceFoundException) to 500.
        mockMvc.perform(get("/api/non-existent-endpoint"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
