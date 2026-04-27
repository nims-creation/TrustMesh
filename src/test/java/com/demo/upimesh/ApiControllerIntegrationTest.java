package com.demo.upimesh;

import com.demo.upimesh.controller.ApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the REST API endpoints using MockMvc.
 * This verifies the actual HTTP request/response cycle, including JSON parsing,
 * @Valid constraints, and GlobalExceptionHandler mappings.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ApiControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testHealthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.db.accounts").exists());
    }

    @Test
    void testServerKeyEndpoint() throws Exception {
        mockMvc.perform(get("/api/server-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").exists())
                .andExpect(jsonPath("$.algorithm").value("RSA-2048 / OAEP-SHA256"));
    }

    @Test
    void testDemoSendValidationRejectsBlankVpa() throws Exception {
        // Missing receiverVpa and invalid negative amount
        String badPayload = """
                {
                  "senderVpa": "alice@demo",
                  "receiverVpa": "",
                  "amount": -50.00,
                  "pin": "1234"
                }
                """;

        mockMvc.perform(post("/api/demo/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badPayload))
                .andExpect(status().isBadRequest()) // @Valid -> 400
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.violations[0]").exists());
    }
}
