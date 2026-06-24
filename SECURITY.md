# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 2.2.x   | ✅ Active           |
| 2.1.x   | ✅ Active           |
| 2.0.x   | ⚠️ Security fixes only |
| 1.0.x   | ❌ Not supported    |
| < 1.0   | ❌ Not supported    |

---

## Reporting a Vulnerability

Security is a top priority for TrustMesh. We treat all vulnerabilities with utmost seriousness.

**Do NOT open a public GitHub issue for security vulnerabilities.**

Please report via GitHub's private [Security Advisories](../../security/advisories/new) feature.

### Triage Process
1. **Acknowledge** receipt within 48 hours
2. **Investigate** and confirm the vulnerability
3. **Develop and test** a patch
4. **Release** the patch and publish a security advisory
5. **Credit** the reporter (unless they prefer anonymity)

---

## Implemented Security Features (v2.0)

### Authentication & Authorization
- **JWT Bridge Node Auth** — `POST /api/bridge/register` issues HS256 signed tokens (24h expiry)
- **JwtAuthFilter** — `OncePerRequestFilter` protects `/api/bridge/ingest` with 401 on missing/invalid tokens
- **Claims** — `sub=deviceId`, `role=BRIDGE_NODE`, `iat`, `exp` embedded in every token

### Encryption
- **Hybrid Encryption** — RSA-2048/OAEP + AES-256-GCM per-packet
  - Relay phones route ciphertext **blindly** — cannot read VPAs, amount, or PIN
  - AES-GCM Authentication Tag — any byte flip causes `AEADBadTagException` on server
  - Per-packet ephemeral AES key — forward secrecy at the packet level
- **Key Storage** — RSA keypair generated at JVM startup; production should use AWS KMS

### Idempotency & Double-Spend Prevention
- **Layer 1** — `ConcurrentHashMap.putIfAbsent(SHA-256(ciphertext))` — atomic CAS, O(1)
- **Layer 2** — Database `UNIQUE INDEX` on `packet_hash` — prevents race in multi-JVM setups
- **Layer 3** — `@Version` optimistic locking — prevents concurrent balance corruption
- **Proven** — k6 stress test: 100 VUs submit same packet, exactly 1 SETTLED, 0 double-debits

### Resilience
- **Resilience4j Circuit Breaker** — opens at 50% failure rate (10-call window)
  - Prevents DB failure storms from cascading into the ingestion pipeline
  - Graceful fallback — returns `CIRCUIT_OPEN` sentinel (never throws to client)
- **Resilience4j Retry** — 3 attempts, 200ms wait for transient DB errors
  - `InsufficientFundsException` deliberately NOT retried (financial rule)

### Replay Attack Protection
- **Timestamp freshness** — `signedAt` field inside AES-GCM encrypted payload
  - Server rejects packets older than 24h or newer than +5 min (future-dated)
  - Attacker cannot modify `signedAt` — GCM tag would break
- **Packet hash dedup** — byte-identical ciphertext → same hash → idempotency drop

### HTTP Security Headers
Applied globally via `SecurityHeadersFilter`:
```
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Content-Security-Policy: default-src 'self'; script-src 'self' cdn.jsdelivr.net; ...
```

### Input Validation
- `@Valid` + `jakarta.validation` constraints on all REST request bodies
- `@NotBlank`, `@Positive`, `@Size`, `@Min`, `@Max` enforced at controller layer
- `GlobalExceptionHandler` returns structured error JSON (never stack traces)

### Production Hardening
- H2 console disabled in `application-prod.properties`
- `spring.jpa.open-in-view=false` — prevents lazy-loading in web tier
- Structured request logging with timing (no PII logged)
- `@Profile("!prod")` on seed data — fake accounts never appear in production

---

## Known Limitations (Demo Mode)

| Limitation | Risk | Production Mitigation |
|---|---|---|
| JWT secret in `application.properties` | Medium | Inject via env var / AWS Secrets Manager |
| HS256 (shared secret) JWT | Low | RS256 with AWS KMS (external verifiers) |
| RSA keypair ephemeral (JVM memory) | Medium | AWS KMS / HashiCorp Vault |
| ConcurrentHashMap idempotency | Low (single JVM) | Redis `SET NX EX` for distributed |
| No rate limiting | Medium | Bucket4j / AWS API Gateway throttling |
| H2 in-memory (dev) | Low (dev only) | PostgreSQL in production |

---

## Security Architecture — Defence in Depth

```
Incoming request
     │
     ▼
[L1] JwtAuthFilter           ← Unauthorized bridge blocked (401)
     │
     ▼
[L2] Idempotency Cache       ← Duplicate packet dropped (atomic CAS)
     │
     ▼
[L3] AES-GCM Tag Check       ← Tampered ciphertext rejected
     │
     ▼
[L4] Freshness Check         ← Stale/future-dated packet rejected
     │
     ▼
[L5] Circuit Breaker         ← DB failure storm isolation
     │
     ▼
[L6] Retry                   ← Transient DB errors recovered
     │
     ▼
[L7] DB UNIQUE constraint    ← Last-resort distributed idempotency
     │
     ▼
[L8] @Version lock           ← Balance corruption prevented
```

**8 independent layers** — any single layer failing does not compromise financial correctness.
