package com.demo.upimesh.controller;

import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.IdempotencyService;
import com.demo.upimesh.service.MeshMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight health check endpoint.
 *
 * Now enriched with MeshMetricsService business counters:
 *   - packets settled / duplicate_dropped / invalid / rejected (since JVM start)
 *   - gossip rounds executed
 *   - bridge uploads attempted
 *
 * Prometheus-level detail is available at /actuator/prometheus (scraped by Grafana).
 * This endpoint serves a human-readable JSON snapshot for dashboards and load balancers.
 *
 * Returns HTTP 503 when the DB is unreachable so load balancers can pull the
 * unhealthy instance from rotation automatically.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Health Check", description = "System health and metrics endpoints")
public class HealthController {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final IdempotencyService idempotency;
    private final MeshMetricsService metrics;

    public HealthController(AccountRepository accounts,
                            TransactionRepository transactions,
                            IdempotencyService idempotency,
                            MeshMetricsService metrics) {
        this.accounts     = accounts;
        this.transactions = transactions;
        this.idempotency  = idempotency;
        this.metrics      = metrics;
    }

    @GetMapping("/health")
    @Operation(
        summary     = "Get System Health",
        description = "Returns UP (200) if the database is reachable, DOWN (503) otherwise. "
                    + "Includes JVM info, business metrics counters, and idempotency cache size."
    )
    public ResponseEntity<Map<String, Object>> health() {
        try {
            long accountCount = accounts.count();
            long txCount      = transactions.count();

            // ── JVM info ─────────────────────────────────────────
            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            Duration uptime   = Duration.ofMillis(uptimeMillis);
            String uptimeStr  = String.format("%dd %02dh %02dm %02ds",
                    uptime.toDaysPart(), uptime.toHoursPart(),
                    uptime.toMinutesPart(), uptime.toSecondsPart());

            Map<String, Object> jvm = new LinkedHashMap<>();
            jvm.put("version",             System.getProperty("java.version"));
            jvm.put("uptimeFormatted",     uptimeStr);
            jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            jvm.put("freeMemoryMb",        Runtime.getRuntime().freeMemory()  / (1024 * 1024));
            jvm.put("maxMemoryMb",         Runtime.getRuntime().maxMemory()   / (1024 * 1024));

            // ── Business metrics (Prometheus counters snapshot) ──
            Map<String, Object> businessMetrics = new LinkedHashMap<>();
            businessMetrics.put("packetsSettled",          metrics.getTotalSettled());
            businessMetrics.put("packetsDuplicateDropped", metrics.getTotalDuplicateDropped());
            businessMetrics.put("packetsInvalid",          metrics.getTotalInvalid());
            businessMetrics.put("packetsRejected",         metrics.getTotalRejected());
            businessMetrics.put("circuitBreakerOpen",      metrics.getTotalCircuitBreakerOpen());
            businessMetrics.put("gossipRounds",            metrics.getTotalGossipRounds());
            businessMetrics.put("bridgeUploads",           metrics.getTotalBridgeUploads());
            businessMetrics.put("prometheusEndpoint",      "/actuator/prometheus");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status",           "UP");
            body.put("timestamp",        Instant.now().toString());
            body.put("db",               Map.of("accounts", accountCount, "transactions", txCount));
            body.put("idempotencyCache", idempotency.size());
            body.put("businessMetrics",  businessMetrics);
            body.put("jvm",              jvm);

            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "DOWN",
                    "error",  e.getMessage()
            ));
        }
    }
}
