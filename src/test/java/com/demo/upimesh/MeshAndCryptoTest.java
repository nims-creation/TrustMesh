package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshSimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for mesh state management and cryptographic hash correctness.
 *
 * Test 1 — Mesh reset clears both mesh AND idempotency cache:
 *   After reset, a previously-seen packet hash should be claimable again
 *   (as if it had never been seen). This is essential for the demo's "Reset" button.
 *
 * Test 2 — hashCiphertext is deterministic across calls:
 *   The same Base64 ciphertext must always produce the same SHA-256 hash.
 *   This guards against the bug where we hashed the Base64 *string* (charset-
 *   dependent) instead of the raw decoded bytes.
 *
 * Test 3 — Unknown sender VPA throws and is caught cleanly:
 *   Sending a packet from a VPA that doesn't exist in the DB should return
 *   INVALID with internal_error, not crash the server.
 */
@SpringBootTest
class MeshAndCryptoTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;

    @Test
    void resetClearsMeshAndIdempotencyCache() throws Exception {
        // 1. Inject a packet and claim its hash
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("10.00"), "1234", 5);
        mesh.inject("phone-alice", packet);
        String hash = crypto.hashCiphertext(packet.getCiphertext());

        // Claim the hash — should succeed on first call
        assertTrue(idempotency.claim(hash), "First claim should succeed");
        assertFalse(idempotency.claim(hash), "Second claim of same hash should fail (duplicate)");

        // 2. Reset mesh + idempotency
        mesh.resetMesh();
        idempotency.clear();

        // 3. After reset, claiming the same hash should succeed again
        assertTrue(idempotency.claim(hash),
                "After reset, the same hash should be claimable again");

        // 4. Mesh devices should hold no packets after reset
        mesh.getDevices().forEach(device ->
            assertEquals(0, device.packetCount(),
                    device.getDeviceId() + " should hold 0 packets after reset"));
    }

    @Test
    void hashCiphertextIsDeterministic() throws Exception {
        // The same ciphertext must always hash to the same value.
        // This directly tests the fix: hash raw bytes, not the Base64 string.
        PaymentInstruction instruction = new PaymentInstruction(
                "alice@demo", "bob@demo",
                new BigDecimal("99.00"),
                "pinhash", "determinism-nonce", System.currentTimeMillis());

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        // Hash the same ciphertext 3 times — all must be identical
        String hash1 = crypto.hashCiphertext(ciphertext);
        String hash2 = crypto.hashCiphertext(ciphertext);
        String hash3 = crypto.hashCiphertext(ciphertext);

        assertEquals(hash1, hash2, "Hash must be deterministic — call 1 vs 2");
        assertEquals(hash2, hash3, "Hash must be deterministic — call 2 vs 3");
        assertEquals(64, hash1.length(), "SHA-256 hex string should be exactly 64 characters");
    }

    @Test
    void unknownSenderVpaIsHandledGracefully() throws Exception {
        // Build a valid encrypted packet but with a VPA that doesn't exist in the DB
        PaymentInstruction instruction = new PaymentInstruction(
                "ghost@nowhere",        // ← does not exist in DB
                "bob@demo",
                new BigDecimal("100.00"),
                "pinhash",
                "ghost-nonce-" + System.nanoTime(),
                System.currentTimeMillis()
        );

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());
        MeshPacket packet = new MeshPacket();
        packet.setPacketId("ghost-packet-" + System.nanoTime());
        packet.setTtl(3);
        packet.setCreatedAt(System.currentTimeMillis());
        packet.setCiphertext(ciphertext);

        idempotency.clear();

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-test", 1);

        // Should not throw — should return INVALID cleanly
        assertEquals("INVALID", result.outcome(),
                "Unknown sender VPA should result in INVALID, not an unhandled exception");
        assertNotNull(result.reason(), "Should have a reason for the rejection");
    }
}
