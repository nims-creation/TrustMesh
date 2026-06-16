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

### 1. Defence in Depth
Every security property is enforced at **multiple independent layers**:
```
Idempotency: ConcurrentHashMap (L1) → DB UNIQUE INDEX (L2)
Integrity:   AES-GCM auth tag (L1) → TTL check (L2) → nonce (L3)
Consistency: Idempotency gate (L1) → @Transactional (L2) → @Version (L3)
```

### 2. Dependency Inversion Principle
All infrastructure concerns hide behind interfaces:
- `IdempotencyService` → `LocalIdempotencyService` (dev) / `RedisIdempotencyService` (prod)
- New implementations require zero changes to business logic

### 3. Profile-Driven Configuration
- `@Profile("!prod")` — fake seed data, ConcurrentHashMap, H2 console
- `@Profile("prod")` — Redis, PostgreSQL, hardened properties

### 4. Standard Java — No External Crypto Libraries
All cryptography uses `javax.crypto` (Java JCE) — no Bouncy Castle dependency. This reduces attack surface and dependency footprint.
