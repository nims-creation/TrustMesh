package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Orchestrates the full server-side pipeline for one inbound packet from a
 * bridge node:
 *
 *   1. Hash the ciphertext.
 *   2. Try to claim that hash via the idempotency cache.
 *      - If already claimed: this is a duplicate. Drop it.
 *   3. Decrypt the ciphertext with the server's private key.
 *      - If decryption fails: tampered or junk. Reject.
 *   4. Check freshness — reject if signedAt is too old (replay protection).
 *   5. Hand off to SettlementService for the actual debit/credit.
 *
 * Constructor injection is used instead of @Autowired field injection because:
 *   - Dependencies are final (immutable after construction)
 *   - Unit tests can inject mocks without a Spring context
 *   - Spring itself recommends constructor injection
 *
 * MeshMetricsService wired to record business outcomes as Prometheus counters
 * and settlement end-to-end latency as a histogram.
 */
@Service
@Slf4j
public class BridgeIngestionService {

    private final HybridCryptoService crypto;
    private final IdempotencyService idempotency;
    private final SettlementService settlement;
    private final MeshMetricsService metrics;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public BridgeIngestionService(HybridCryptoService crypto,
                                  IdempotencyService idempotency,
                                  SettlementService settlement,
                                  MeshMetricsService metrics) {
        this.crypto     = crypto;
        this.idempotency = idempotency;
        this.settlement  = settlement;
        this.metrics     = metrics;
    }

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        long startNanos = System.nanoTime();
        try {
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ── Idempotency gate ────────────────────────────────
            if (!idempotency.claim(packetHash)) {
                log.info("DUPLICATE packet {}… from bridge {} — dropped",
                        packetHash.substring(0, 12), bridgeNodeId);
                metrics.recordDuplicateDropped();
                return IngestResult.duplicate(packetHash);
            }

            // ── Decrypt ─────────────────────────────────────────
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                log.warn("Decryption failed for packet {}…: {}",
                        packetHash.substring(0, 12), e.getMessage());
                metrics.recordInvalid();
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            // ── Freshness check (replay protection) ─────────────
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {}… too old ({}s), rejected", packetHash.substring(0, 12), ageSeconds);
                metrics.recordInvalid();
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) {
                metrics.recordInvalid();
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ── Settle ──────────────────────────────────────────
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            metrics.recordSettled();
            metrics.recordSettlementLatency(System.nanoTime() - startNanos);
            return IngestResult.settled(packetHash, tx);

        } catch (InsufficientFundsException e) {
            log.warn("Insufficient funds for {}: {}", e.getSenderVpa(), e.getMessage());
            metrics.recordRejected();
            return IngestResult.invalid(e.getMessage(), "insufficient_funds");
        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            metrics.recordInvalid();
            return IngestResult.invalid("?", "internal_error: " + e.getMessage());
        }
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
    }
}
