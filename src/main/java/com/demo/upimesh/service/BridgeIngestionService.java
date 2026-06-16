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
 * Orchestrates the full server-side pipeline for one inbound packet from a bridge node:
 *
 *   1. Hash ciphertext → idempotency gate
 *   2. Decrypt → AES-GCM tag verifies integrity
 *   3. Freshness check → replay protection
 *   4. Settle → ACID debit/credit
 *
 * Wired with:
 *   - MeshMetricsService  → Prometheus counters + settlement latency histogram
 *   - MeshEventPublisher  → real-time WebSocket push to dashboard subscribers
 */
@Service
@Slf4j
public class BridgeIngestionService {

    private final HybridCryptoService crypto;
    private final IdempotencyService idempotency;
    private final SettlementService settlement;
    private final MeshMetricsService metrics;
    private final MeshEventPublisher events;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public BridgeIngestionService(HybridCryptoService crypto,
                                  IdempotencyService idempotency,
                                  SettlementService settlement,
                                  MeshMetricsService metrics,
                                  MeshEventPublisher events) {
        this.crypto      = crypto;
        this.idempotency = idempotency;
        this.settlement  = settlement;
        this.metrics     = metrics;
        this.events      = events;
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
                events.packetDuplicate(packetHash, bridgeNodeId);
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
                events.packetInvalid("decryption_failed", bridgeNodeId);
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            // ── Freshness check (replay protection) ─────────────
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {}… too old ({}s), rejected", packetHash.substring(0, 12), ageSeconds);
                metrics.recordInvalid();
                events.packetInvalid("stale_packet", bridgeNodeId);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) {
                metrics.recordInvalid();
                events.packetInvalid("future_dated", bridgeNodeId);
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ── Settle ──────────────────────────────────────────
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);

            // Circuit breaker fallback returns CIRCUIT_OPEN status instead of throwing
            if (tx.getStatus() == Transaction.Status.CIRCUIT_OPEN) {
                log.warn("Settlement circuit OPEN — packet {} rejected", packetHash.substring(0, 12));
                metrics.recordRejected();
                events.packetInvalid("circuit_breaker_open", bridgeNodeId);
                return IngestResult.invalid(packetHash, "circuit_breaker_open");
            }

            metrics.recordSettled();
            metrics.recordSettlementLatency(System.nanoTime() - startNanos);
            events.packetSettled(
                    packetHash,
                    instruction.getSenderVpa(),
                    instruction.getReceiverVpa(),
                    instruction.getAmount().doubleValue(),
                    bridgeNodeId, hopCount, tx.getId()
            );
            return IngestResult.settled(packetHash, tx);

        } catch (InsufficientFundsException e) {
            log.warn("Insufficient funds for {}: {}", e.getSenderVpa(), e.getMessage());
            metrics.recordRejected();
            events.packetInvalid("insufficient_funds:" + e.getSenderVpa(), bridgeNodeId);
            return IngestResult.invalid(e.getMessage(), "insufficient_funds");
        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            metrics.recordInvalid();
            events.packetInvalid("internal_error", bridgeNodeId);
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
