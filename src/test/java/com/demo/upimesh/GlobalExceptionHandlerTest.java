package com.demo.upimesh;

import com.demo.upimesh.controller.ApiController;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BridgeIngestionService bridgeIngestionService;

    @Test
    void testMethodArgumentNotValidReturns400WithFieldErrors() throws Exception {
        // Missing ciphertext
        String badMeshPacket = """
                {
                  "packetId": "some-uuid",
                  "ttl": 5,
                  "createdAt": 1700000000000
                }
                """;

        mockMvc.perform(post("/api/bridge/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badMeshPacket))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.violations[0]").value("ciphertext: must not be blank"));
    }

    @Test
    void testInsufficientFundsExceptionReturns422() throws Exception {
        String validMeshPacket = """
                {
                  "packetId": "some-uuid",
                  "ttl": 5,
                  "createdAt": 1700000000000,
                  "ciphertext": "base64blob"
                }
                """;

        // Mock the service to throw InsufficientFundsException
        when(bridgeIngestionService.ingest(any(MeshPacket.class), anyString(), anyInt()))
                .thenThrow(new InsufficientFundsException("alice@demo", new BigDecimal("100"), new BigDecimal("5000")));

        mockMvc.perform(post("/api/bridge/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validMeshPacket))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Insufficient Funds"))
                .andExpect(jsonPath("$.senderVpa").value("alice@demo"))
                .andExpect(jsonPath("$.available").value(100))
                .andExpect(jsonPath("$.requested").value(5000));
    }
}
