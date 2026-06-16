# Architecture Decision Records — TrustMesh

This directory contains Architecture Decision Records (ADRs) for TrustMesh.

An ADR documents a significant architectural decision: the context that led to it, the options considered, the decision made, and its consequences. ADRs are immutable records — once accepted, they are never deleted (only superseded by a new ADR).

> **Format inspired by:** [Michael Nygard's ADR template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)

---

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [ADR-001](./ADR-001-hybrid-encryption.md) | Hybrid Encryption (RSA-OAEP + AES-256-GCM) | ✅ Accepted |
| [ADR-002](./ADR-002-aes-gcm-not-cbc.md) | AES-256-GCM over AES-256-CBC | ✅ Accepted |
| [ADR-003](./ADR-003-optimistic-locking.md) | Optimistic Locking (`@Version`) over Pessimistic Locking | ✅ Accepted |
| [ADR-004](./ADR-004-idempotency-concurrenthashmap.md) | `ConcurrentHashMap.putIfAbsent()` for In-Process Idempotency | ✅ Accepted |
| [ADR-005](./ADR-005-redis-production-idempotency.md) | Redis `SET NX EX` for Production Idempotency | ✅ Accepted (prod profile) |

---

## Key Architectural Themes

### 1. Defence in Depth — 8 Layers (v2.0)
Every security and consistency property is enforced at multiple independent layers:
```
Auth:        JwtAuthFilter (L1)
Idempotency: ConcurrentHashMap putIfAbsent (L2) → DB UNIQUE INDEX (L7)
Integrity:   AES-GCM auth tag (L3) → Freshness check (L4)
Resilience:  Circuit Breaker (L5) → Retry (L6)
Consistency: @Transactional (L7) → @Version optimistic lock (L8)
```

### 2. Dependency Inversion Principle
All infrastructure concerns hide behind interfaces:
- `IdempotencyService` → `LocalIdempotencyService` (dev) / `RedisIdempotencyService` (prod)
- New implementations require zero changes to business logic (`BridgeIngestionService`)

### 3. Profile-Driven Configuration
- `@Profile("!prod")` — fake seed data, ConcurrentHashMap, H2 console
- `@Profile("prod")` — Redis, PostgreSQL, hardened properties

### 4. Standard Java — No External Crypto Libraries
All cryptography uses `javax.crypto` (Java JCE) — no Bouncy Castle dependency. Reduces attack surface and dependency footprint.

### 5. Resilience Ordering: Retry → CircuitBreaker → @Transactional (v2.0)
The correct Resilience4j annotation stacking order:
```
@Retry wraps @CircuitBreaker wraps @Transactional
```
Each retry attempt is a fresh circuit breaker call. The breaker counts the **final** failure (after all retries), not each individual retry attempt.

### 6. Sentinel Fallback Pattern (v2.0)
Circuit breaker fallback returns a sentinel value (`CIRCUIT_OPEN` Transaction status) instead of throwing. The caller (`BridgeIngestionService`) handles it gracefully — pipeline continues without propagating exceptions to the HTTP layer.

### 7. Non-Fatal Side Effects (v2.0)
WebSocket events (`MeshEventPublisher`) and Prometheus counters (`MeshMetricsService`) are deliberately non-fatal. A WebSocket connection failure or metrics recording error **never** breaks the payment pipeline. `try-catch` with `log.warn` — observability infrastructure cannot affect financial correctness.
