package com.demo.upimesh.controller;

import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight health check endpoint.
 *
 * Why not use Spring Boot Actuator?
 *   Actuator would work, but it adds many auto-configured endpoints that
 *   expose internal metrics unless carefully locked down. For a demo
 *   this custom endpoint is simpler and shows the interviewer you understand
 *   what a health check should DO, not just which dependency to add.
 *
 * A proper production health check would also:
 *   - Verify the DB connection with a SELECT 1
 *   - Verify Redis connectivity (ping)
 *   - Report disk space for log volumes
 *   - Return HTTP 503 if any dependency is unhealthy (so load balancers
 *     can automatically pull the instance from rotation)
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Health Check", description = "System health and metrics endpoints")
public class HealthController {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final IdempotencyService idempotency;

    public HealthController(AccountRepository accounts,
                            TransactionRepository transactions,
                            IdempotencyService idempotency) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.idempotency = idempotency;
    }

    @GetMapping("/health")
    @Operation(summary = "Get System Health", description = "Returns UP if the database is reachable, DOWN (503) otherwise. Also provides basic system metrics.")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            long accountCount  = accounts.count();
            long txCount       = transactions.count();

            long uptimeMillis  = ManagementFactory.getRuntimeMXBean().getUptime();
            Duration uptime    = Duration.ofMillis(uptimeMillis);
            String uptimeStr   = String.format("%dd %02dh %02dm %02ds",
                    uptime.toDaysPart(), uptime.toHoursPart(),
                    uptime.toMinutesPart(), uptime.toSecondsPart());

            Map<String, Object> jvm = new LinkedHashMap<>();
            jvm.put("version",            System.getProperty("java.version"));
            jvm.put("uptimeFormatted",    uptimeStr);
            jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            jvm.put("freeMemoryMb",       Runtime.getRuntime().freeMemory() / (1024 * 1024));
            jvm.put("maxMemoryMb",        Runtime.getRuntime().maxMemory()  / (1024 * 1024));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status",            "UP");
            body.put("timestamp",         Instant.now().toString());
            body.put("db",                Map.of("accounts", accountCount, "transactions", txCount));
            body.put("idempotencyCache",  idempotency.size());
            body.put("jvm",               jvm);

            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // If the DB is unreachable, return 503 so load balancers stop routing here
            return ResponseEntity.status(503).body(Map.of(
                    "status", "DOWN",
                    "error",  e.getMessage()
            ));
        }
    }
}
