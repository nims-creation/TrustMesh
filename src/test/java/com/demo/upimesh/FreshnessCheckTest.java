package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the freshness / replay-attack protection logic in BridgeIngestionService.
 *
 * Why freshness matters:
 *   An attacker could capture a valid ciphertext and replay it weeks later.
 *   The signedAt field (inside the encrypted payload, tamper-proof) lets the
 *   server reject any packet older than 24 hours.
 *
 *   The attacker cannot change signedAt without breaking the AES-GCM auth tag.
 */
@SpringBootTest
class FreshnessCheckTest {

    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;

    @BeforeEach
    void clearCache() {
        idempotency.clear();
    }

    @Test
    void stalePacketOlderThan24HoursIsRejected() throws Exception {
        // Build a packet with signedAt = 25 hours ago
        long twentyFiveHoursAgo = System.currentTimeMillis() - (25L * 60 * 60 * 1000);

        PaymentInstruction staleInstruction = new PaymentInstruction(
                "alice@demo", "bob@demo",
                new BigDecimal("200.00"),
                "pinhash",
                "stale-nonce-" + System.nanoTime(),
                twentyFiveHoursAgo          // ← too old, should be rejected
        );

        String ciphertext = crypto.encrypt(staleInstruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId("stale-packet-" + System.nanoTime());
        packet.setTtl(3);
        packet.setCreatedAt(twentyFiveHoursAgo);
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome(),
                "A packet older than 24h should be rejected as stale");
        assertEquals("stale_packet", result.reason(),
                "Reason should indicate stale_packet, not internal_error");
    }

    @Test
    void freshPacketWithin24HoursIsAccepted() throws Exception {
        // Build a packet with signedAt = 1 hour ago (well within the 24h window)
        long oneHourAgo = System.currentTimeMillis() - (60L * 60 * 1000);

        PaymentInstruction freshInstruction = new PaymentInstruction(
                "carol@demo", "dave@demo",
                new BigDecimal("50.00"),
                "pinhash",
                "fresh-nonce-" + System.nanoTime(),
                oneHourAgo
        );

        String ciphertext = crypto.encrypt(freshInstruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId("fresh-packet-" + System.nanoTime());
        packet.setTtl(3);
        packet.setCreatedAt(oneHourAgo);
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        // A 1-hour old packet should settle successfully
        assertEquals("SETTLED", result.outcome(),
                "A packet within the 24h window should be settled");
        assertNotNull(result.transactionId(), "Settled packet must have a transactionId");
    }

    @Test
    void futureDatedPacketIsRejected() throws Exception {
        // signedAt = 10 minutes in the future (exceeds 5-min clock skew tolerance)
        long tenMinutesAhead = System.currentTimeMillis() + (10L * 60 * 1000);

        PaymentInstruction futureInstruction = new PaymentInstruction(
                "bob@demo", "alice@demo",
                new BigDecimal("75.00"),
                "pinhash",
                "future-nonce-" + System.nanoTime(),
                tenMinutesAhead
        );

        String ciphertext = crypto.encrypt(futureInstruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId("future-packet-" + System.nanoTime());
        packet.setTtl(3);
        packet.setCreatedAt(tenMinutesAhead);
        packet.setCiphertext(ciphertext);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        assertEquals("INVALID", result.outcome(),
                "A future-dated packet beyond clock-skew tolerance should be rejected");
        assertEquals("future_dated", result.reason());
    }
}
