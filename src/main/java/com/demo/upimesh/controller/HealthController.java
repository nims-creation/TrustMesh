package com.demo.upimesh.controller;

import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
    public ResponseEntity<Map<String, Object>> health() {
        try {
            long accountCount = accounts.count();
            long txCount = transactions.count();
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "timestamp", Instant.now().toString(),
                    "db", Map.of(
                            "accounts", accountCount,
                            "transactions", txCount
                    ),
                    "idempotencyCache", idempotency.size()
            ));
        } catch (Exception e) {
            // If the DB is unreachable, return 503 so load balancers stop routing here
            return ResponseEntity.status(503).body(Map.of(
                    "status", "DOWN",
                    "error", e.getMessage()
            ));
        }
    }
}
