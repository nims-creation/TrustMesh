package com.demo.upimesh.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Central Micrometer metrics service for TrustMesh.
 *
 * Exposes custom business counters and timers at /actuator/prometheus:
 *   - trustmesh_packets_settled_total
 *   - trustmesh_packets_duplicate_dropped_total
 *   - trustmesh_packets_invalid_total
 *   - trustmesh_packets_rejected_total      (insufficient funds)
 *   - trustmesh_settlement_latency_seconds  (histogram + percentiles)
 *   - trustmesh_gossip_rounds_total
 *   - trustmesh_bridge_uploads_total
 *
 * Wired into BridgeIngestionService and MeshSimulatorService.
 * In production, Prometheus scrapes /actuator/prometheus every 15s.
 */
@Slf4j
@Service
public class MeshMetricsService {

    // ── Counters ────────────────────────────────────────────────
    private final Counter settledCounter;
    private final Counter duplicateDroppedCounter;
    private final Counter invalidCounter;
    private final Counter rejectedCounter;
    private final Counter gossipRoundsCounter;
    private final Counter bridgeUploadsCounter;

    // ── Timers ──────────────────────────────────────────────────
    private final Timer settlementLatencyTimer;

    public MeshMetricsService(MeterRegistry registry) {
        this.settledCounter = Counter.builder("trustmesh.packets.settled")
                .description("Total packets that successfully settled (debit+credit committed)")
                .register(registry);

        this.duplicateDroppedCounter = Counter.builder("trustmesh.packets.duplicate_dropped")
                .description("Packets dropped by idempotency layer (already seen ciphertext hash)")
                .register(registry);

        this.invalidCounter = Counter.builder("trustmesh.packets.invalid")
                .description("Packets rejected due to tampered ciphertext or failed decryption")
                .register(registry);

        this.rejectedCounter = Counter.builder("trustmesh.packets.rejected")
                .description("Packets rejected at business layer (insufficient funds, stale timestamp)")
                .register(registry);

        this.gossipRoundsCounter = Counter.builder("trustmesh.gossip.rounds")
                .description("Total gossip rounds executed (each round hops packets one step)")
                .register(registry);

        this.bridgeUploadsCounter = Counter.builder("trustmesh.bridge.uploads")
                .description("Total bridge upload attempts (flush calls)")
                .register(registry);

        this.settlementLatencyTimer = Timer.builder("trustmesh.settlement.latency")
                .description("Time from bridge ingest request to DB commit (end-to-end settlement latency)")
                .publishPercentiles(0.50, 0.95, 0.99)   // P50, P95, P99
                .publishPercentileHistogram()
                .register(registry);
    }

    // ── Public recording methods ─────────────────────────────────

    public void recordSettled() {
        settledCounter.increment();
        log.debug("[metrics] packet settled +1 (total={})", settledCounter.count());
    }

    public void recordDuplicateDropped() {
        duplicateDroppedCounter.increment();
    }

    public void recordInvalid() {
        invalidCounter.increment();
    }

    public void recordRejected() {
        rejectedCounter.increment();
    }

    public void recordGossipRound() {
        gossipRoundsCounter.increment();
    }

    public void recordBridgeUpload() {
        bridgeUploadsCounter.increment();
    }

    /**
     * Records settlement end-to-end latency.
     *
     * Usage:
     * <pre>
     *   long start = System.nanoTime();
     *   // ... settlement logic ...
     *   metrics.recordSettlementLatency(System.nanoTime() - start);
     * </pre>
     */
    public void recordSettlementLatency(long durationNanos) {
        settlementLatencyTimer.record(Duration.ofNanos(durationNanos));
    }

    // ── Snapshot getters (for /api/health enrichment) ───────────

    public long getTotalSettled()          { return (long) settledCounter.count(); }
    public long getTotalDuplicateDropped() { return (long) duplicateDroppedCounter.count(); }
    public long getTotalInvalid()          { return (long) invalidCounter.count(); }
    public long getTotalRejected()         { return (long) rejectedCounter.count(); }
    public long getTotalGossipRounds()     { return (long) gossipRoundsCounter.count(); }
    public long getTotalBridgeUploads()    { return (long) bridgeUploadsCounter.count(); }
}
