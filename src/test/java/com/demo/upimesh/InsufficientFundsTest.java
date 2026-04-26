package com.demo.upimesh;

import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the insufficient-balance path in SettlementService.
 *
 * dave@demo starts with ₹500. If he tries to send ₹1000, the payment
 * must be rejected — not crash the server, not silently succeed.
 *
 * This test verifies:
 *   1. The ingestion result is INVALID (InsufficientFundsException caught)
 *   2. Dave's balance is NOT changed
 *   3. The recipient's balance is NOT changed
 */
@SpringBootTest
class InsufficientFundsTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;

    @BeforeEach
    void clearCache() {
        idempotency.clear();
    }

    @Test
    void paymentExceedingBalanceIsRejected() throws Exception {
        // dave@demo has only ₹500 — trying to send ₹1000 should fail
        BigDecimal daveBefore = accounts.findById("dave@demo").orElseThrow().getBalance();
        BigDecimal carolBefore = accounts.findById("carol@demo").orElseThrow().getBalance();

        // Create a packet for ₹1000 from Dave (who only has ₹500)
        var packet = demoService.createPacket(
                "dave@demo", "carol@demo",
                new BigDecimal("1000.00"),
                "1234", 5);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        // Should be INVALID due to InsufficientFundsException
        assertEquals("INVALID", result.outcome(),
                "Payment exceeding balance should be rejected as INVALID");
        assertEquals("insufficient_funds", result.reason(),
                "Reason should be insufficient_funds");
        assertNull(result.transactionId(),
                "No transaction should be recorded for a rejected payment");

        // Balances must be unchanged — no money moved
        BigDecimal daveAfter = accounts.findById("dave@demo").orElseThrow().getBalance();
        BigDecimal carolAfter = accounts.findById("carol@demo").orElseThrow().getBalance();
        assertEquals(daveBefore, daveAfter, "Dave's balance must not change on rejection");
        assertEquals(carolBefore, carolAfter, "Carol's balance must not change on rejection");
    }

    @Test
    void paymentExactlyMatchingBalanceSucceeds() throws Exception {
        // dave@demo has ₹500 — sending exactly ₹500 should succeed (edge case)
        BigDecimal daveBefore = accounts.findById("dave@demo").orElseThrow().getBalance();

        // This may succeed or fail depending on whether a previous test already spent Dave's balance.
        // We test the logic: if Dave has exactly the amount, it should not be rejected as insufficient.
        if (daveBefore.compareTo(new BigDecimal("500.00")) < 0) {
            // Skip if a previous test already drained Dave's account
            return;
        }

        var packet = demoService.createPacket(
                "dave@demo", "carol@demo",
                daveBefore,          // send exactly what Dave has
                "1234", 5);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-edge", 1);

        // Should settle — sending exactly your balance is valid
        assertEquals("SETTLED", result.outcome(),
                "Sending exactly the available balance should succeed");
    }
}
