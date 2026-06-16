# TrustMesh — Interview Notes
> **Padh le bhai, interview mein kaam aayega! 🎯**
> v2.0 — 25+ questions with full answers

---

## 🚀 Project Overview (30-second pitch)

**Kya banaya?**
Ek Spring Boot backend jo offline UPI payments ko simulate karta hai — bina internet ke, Bluetooth mesh ke through.

**Core Idea:**
- Tum basement mein ho, internet nahi hai
- Payment encrypt karke nearby phones pe bhejte ho (Bluetooth gossip)
- Koi bhi phone bahar jaake internet pakad le, payment upload kar deta hai
- Backend: JWT verify → decrypt → idempotency → circuit breaker → settle → WebSocket push

**5 Hard Problems jo solve kiye:**
1. Untrusted relay ne packet carry kiya — tamper na ho? → **Hybrid Encryption (AES-GCM)**
2. 3 bridge nodes same payment leke aaye ek saath → **Atomic Idempotency (putIfAbsent)**
3. Purana packet replay kar de → **Freshness Window + Hash Dedup**
4. Rogue bridge node payment inject kare → **JWT Authentication**
5. DB fail hote hain cascade ho jaaye → **Resilience4j Circuit Breaker**

---

## 📦 Tech Stack & Kyun Choose Kiya

| Technology | Version | Kyun? |
|---|---|---|
| **Spring Boot** | 3.3.5 | Industry standard, auto-config |
| **Java** | 17 | LTS version, Records support |
| **H2 (Dev) / PostgreSQL (Prod)** | - | H2 = zero setup, Postgres = production |
| **Spring Data JPA + Hibernate** | - | Repository pattern, @Version |
| **JJWT** | 0.12.6 | HS256 JWT — stateless bridge auth |
| **Resilience4j** | 2.2.0 | Circuit Breaker + Retry (AOP) |
| **Micrometer + Prometheus** | - | Custom business metrics |
| **Spring WebSocket (STOMP)** | - | Real-time dashboard events |
| **k6** | latest | Load testing — idempotency proof |
| **Lombok** | 1.18.x | @Data, @Slf4j boilerplate |
| **Flyway** | - | Versioned DB migrations |

---

## 🔐 Cryptography — Interview ka IMPORTANT Topic

### Hybrid Encryption (RSA-OAEP + AES-256-GCM)

**Q: RSA directly use kyun nahi kiya?**
RSA-2048 sirf ~245 bytes encrypt kar sakta hai. Hamara JSON payload usse bada hai. Solution = Hybrid (same as TLS, PGP, Signal).

**How it works:**
```
1. Fresh AES-256 key generate karo (is packet ke liye only)
2. Payment JSON → AES-256-GCM encrypt   ← fast + authenticated encryption
3. AES key → RSA-OAEP encrypt (server pubkey se)  ← only server decrypts
4. Pack: [256 bytes RSA-encrypted AES key][12 bytes IV][AES ciphertext + 16-byte GCM tag]
5. Base64 encode → send
```

**Q: AES-GCM kyun, sirf AES-CBC kyun nahi?**
- AES-CBC sirf encrypt karta hai, integrity verify nahi karta
- AES-GCM = **Authenticated Encryption (AEAD)** — ek bit bhi flip ho → `AEADBadTagException`
- GCM internally GHASH MAC compute karta hai → tamper impossible
- TLS 1.3 bhi GCM use karta hai — industry standard

**Q: Idempotency key `SHA-256(ciphertext)` kyun, `packetId` kyun nahi?**
- `packetId` malicious relay rewrite kar sakta hai
- `SHA-256(ciphertext)` = same packet → byte-identical ciphertext → same hash (deterministic)
- Ciphertext AES-GCM authenticated hai → tampered ciphertext = different hash = new entry = decrypt fail

---

## ⚛️ Idempotency — Interview ka Star Topic

**Q: Problem kya thi?**
3 bridge nodes same packet carry kar rahe hain. Teeno ek saath `/api/bridge/ingest` pe POST karte hain. Agar naively process karo → ₹500 ki jagah ₹1500 debit.

**Solution: `ConcurrentHashMap.putIfAbsent()` — Atomic CAS**
```java
Instant prev = seen.putIfAbsent(packetHash, now);
return prev == null; // true = pehli baar, false = duplicate
```

**Q: `putIfAbsent` atomic kyun hai?**
ConcurrentHashMap internal segment-level locking use karta hai. 100 threads ek saath call karein — sirf ek ko `null` milega. Baaki sab ko existing entry. JVM guarantees — no synchronized block needed.

**k6 proof — 100 VUs, 1 packet:**
```
trustmesh_packets_settled_total:  1
trustmesh_packets_duplicate_dropped_total: 99
trustmesh_idempotency_violations: 0  ← threshold: MUST be 0
```

**Production mein:**
`ConcurrentHashMap` → **Redis `SET key NX EX 86400`** — same semantics, distributed.
See [ADR-005](docs/adr/ADR-005-redis-production-idempotency.md)

---

## 🔒 Optimistic Locking — @Version

**Q: @Version kya karta hai?**
```java
@Version private Long version; // Account.java
```
JPA update pe version column check karta hai automatically:
- Thread 1 reads version=5, Thread 2 reads version=5
- Thread 1 saves → version becomes 6
- Thread 2 saves with version=5 → `OptimisticLockingFailureException` ❌

**Q: Pessimistic locking se better kyun?**
Pessimistic = DB row lock → contention + deadlock risk + slow.
Optimistic = no lock on read → concurrent reads, version conflict sirf tabhi handle karo jab ho (rare in payments).

**Q: Resilience4j Retry @Version se kaisa interact karta hai?**
`@Retry` `OptimisticLockingFailureException` pe retry karta hai — next attempt fresh version read karta hai → success most of the time. Ye defense in depth ka Layer 3 hai.

---

## 🔑 JWT Authentication — Bridge Node

**Q: JWT kyun, API keys kyun nahi?**
- API keys = DB lookup per request — stateful
- JWT = HMAC verify karo, claims read karo — no DB needed — stateless
- JWT expiry = 24h (matches UPI offline packet window)
- Claims: `sub=deviceId`, `role=BRIDGE_NODE`, `iat`, `exp`

**Q: HS256 kyun, RS256 kyun nahi?**
- HS256: same secret signs + verifies — single service, simpler, faster
- RS256: private key signs, public key verifies — needed if external services verify tokens
- Production: RS256 with AWS KMS private key (private key never leaves HSM)

**Flow:**
```
Bridge → POST /api/bridge/register {deviceId: "my-bridge-01"}
Server → issues JWT (24h, HS256)
Bridge → POST /api/bridge/ingest + "Authorization: Bearer <token>"
JwtAuthFilter → validates → sets request attribute "authenticatedBridgeNodeId"
Controller → JWT deviceId over X-Bridge-Node-Id header (authenticated wins)
```

**Q: `/api/bridge/ingest` pe hi filter kyun, sab pe kyun nahi?**
Dashboard demo unaffected rehna chahiye (uses `/api/mesh/flush` internally). Real bridge nodes production mein `/api/bridge/ingest` use karte hain — wahi protect karna tha.

---

## 🛡️ Resilience4j — Circuit Breaker + Retry

**Q: Circuit Breaker kya hota hai?**
Ek switch jo DB failure storm mein trip kar deta hai — downstream system ko recover ka time deta hai.

**States:**
```
CLOSED → normal operation, all calls pass through
OPEN   → circuit tripped, calls REJECTED immediately (no DB hit)
HALF-OPEN → 3 probe calls allowed, if pass → CLOSED, if fail → OPEN
```

**TrustMesh config:**
```
slidingWindowSize=10, failureRateThreshold=50
→ 5 out of 10 calls fail → circuit OPEN
waitDurationInOpenState=10s → after 10s → HALF-OPEN
permittedNumberOfCallsInHalfOpenState=3 → probe calls
```

**Q: Retry aur CircuitBreaker ka order kyun Retry > CB?**
```
@Retry → @CircuitBreaker → @Transactional → DB
```
Har retry attempt ek fresh CB call hai. CB counts final outcome (after retries), not each retry attempt. Isliye CB accurately tracks real failure rate.

**Q: InsufficientFundsException retry kyun nahi hoti?**
Business rule hai — balance low hai. Retry karoge toh bhi same result. Retry sirf transient errors ke liye (JpaSystemException, OptimisticLockingFailureException).

**Fallback pattern:**
```java
public Transaction settleFallback(..., Throwable cause) {
    // Circuit OPEN → return sentinel stub with CIRCUIT_OPEN status
    // BridgeIngestionService handles it gracefully
    // No exception propagated — pipeline continues cleanly
}
```

---

## 📊 Observability — Prometheus Metrics

**Custom Micrometer counters:**
```
trustmesh_packets_settled_total            ← every successful debit+credit
trustmesh_packets_duplicate_dropped_total  ← idempotency gate drops
trustmesh_packets_invalid_total            ← tampered/expired/future-dated
trustmesh_packets_rejected_total           ← insufficient funds
trustmesh_packets_circuit_open_total       ← circuit breaker rejections
trustmesh_gossip_rounds_total              ← gossip simulation rounds
trustmesh_bridge_uploads_total             ← bridge upload attempts
trustmesh_settlement_latency_seconds       ← P50/P95/P99 histogram
```

**Q: Actuator use nahi kiya, custom metrics kyun?**
Actuator ke auto-metrics JVM + HTTP level pe hain. Business level metrics (settled, duplicates, latency percentiles) apne aap nahi aate — `MeshMetricsService` unhe explicitly record karta hai.

**Grafana dashboard mein kya dikhega?**
- Real-time settlement rate
- Idempotency effectiveness (duplicate % of total)
- P99 latency trend over time
- Circuit breaker open events

---

## ⚡ WebSocket — Real-Time Events

**Q: Polling se WebSocket kyun better hai?**
- Polling = `setInterval(refresh, 3000)` → 3s lag, unnecessary HTTP requests
- WebSocket = server push → event instant dikhta hai, no lag, no wasted requests

**STOMP Protocol:**
```
SockJS (WebSocket with HTTP long-poll fallback)
  └── STOMP (messaging protocol over WebSocket)
        └── /topic/mesh-events (broadcast topic)
              └── JSON event payload {type, icon, timestamp, ...data}
```

**7 event types:**
`PACKET_INJECTED`, `GOSSIP_ROUND`, `BRIDGE_UPLOAD`, `PACKET_SETTLED`, `PACKET_DUPLICATE`, `PACKET_INVALID`, `MESH_RESET`

**Q: WebSocket fail ho jaaye toh?**
`MeshEventPublisher` mein try-catch hai — `log.warn` sirf. Payment pipeline kabhi WebSocket exception se break nahi hoga. Non-fatal design.

---

## 🏗️ Architecture Decisions (5 ADRs)

| ADR | Decision | Rejected Alternative |
|---|---|---|
| ADR-001 | RSA-OAEP + AES-GCM hybrid | Pure RSA (size limit), Pure AES (key distribution) |
| ADR-002 | AES-GCM over AES-CBC | CBC has padding oracle vulnerability, no integrity |
| ADR-003 | @Version optimistic locking | SELECT FOR UPDATE (lock contention, deadlocks) |
| ADR-004 | ConcurrentHashMap.putIfAbsent | synchronized, AtomicReference, DB-only |
| ADR-005 | Redis SET NX EX (prod plan) | DB-only (too slow), Zookeeper (overkill) |

---

## 🧪 Testing Strategy — 31 Tests

**Q: CountDownLatch kyun use kiya concurrency test mein?**
Bina latch ke threads CI mein sequentially run ho sakte hain. Latch ensure karta hai:
```java
CountDownLatch go = new CountDownLatch(1);
// sabhi threads ready ho jaate hain go.await() pe
go.countDown(); // teeno ek saath start
```

**Q: k6 test mein `trustmesh_idempotency_violations` threshold 0 kyun?**
Financial correctness requirement hai — ek bhi double-debit = failure. Threshold = hard gate in CI.

**Concurrency proof (100 VUs):**
```
✓ settled == 1
✓ duplicates == 99
✗ violations: 0 (MUST be 0)
```

---

## 📈 Production Evolution Map

| Demo mein | Production mein | Kyun? |
|---|---|---|
| H2 in-memory | PostgreSQL | Persistence, ACID |
| ConcurrentHashMap | Redis SETNX | Distributed idempotency |
| HS256 JWT | RS256 with AWS KMS | Private key in HSM |
| In-process Circuit Breaker | Redis-backed / Istio | Distributed resilience state |
| H2 console accessible | H2 console disabled | `application-prod.properties` |
| Prometheus local | Grafana Cloud | Centralized observability |
| Manual bridge registration | mTLS + certificate rotation | Zero-trust bridge auth |
| Console logging | Structured JSON + SIEM | Alert on INVALID rate spike |

---

## 💡 25+ Interview Questions with Answers

1. **Hybrid encryption kyun?** → RSA ~245 bytes limit. AES fast+authenticated. Hybrid = best of both.

2. **AES-GCM specifically kyun?** → AEAD — tamper detect on any bit flip. CBC has padding oracle.

3. **Idempotency kaise guarantee karte ho?** → `ConcurrentHashMap.putIfAbsent` atomic CAS. k6 proof: 100 VUs → 1 settled.

4. **Optimistic vs Pessimistic locking kab?** → Optimistic = low contention (payments). Pessimistic = high write contention (inventory).

5. **JWT vs API keys?** → JWT stateless (no DB lookup). Claims carry deviceId + role + expiry.

6. **HS256 vs RS256?** → HS256 = single service, simpler. RS256 = multiple services need to verify.

7. **Circuit breaker kya hota hai?** → Failure count ke baad trip karo, downstream ko recover karne do. Cascade prevent.

8. **Retry order: Retry > CircuitBreaker kyun?** → Har retry fresh CB call. CB counts final failure, not per-retry.

9. **InsufficientFunds retry kyun nahi?** → Business rule, not transient error. Retry = same result = wasted attempts.

10. **WebSocket vs polling?** → Server push = instant, no wasted requests. Polling = 3s lag, unnecessary HTTP.

11. **SockJS kyun?** → WebSocket blocked in some corporate proxies. SockJS HTTP long-poll fallback.

12. **STOMP kyun plain WebSocket nahi?** → STOMP = messaging protocol with topics. `/topic/mesh-events` = pub/sub model.

13. **Prometheus metrics custom kyun, Actuator kafi nahi?** → Actuator = JVM/HTTP metrics. Business metrics (settled, duplicates, P99 latency) require explicit instrumentation.

14. **putIfAbsent atomic kyun hai?** → ConcurrentHashMap segment locking — 100 threads call karo, sirf 1 ko null milega.

15. **Ciphertext hash kyun, packetId hash kyun nahi?** → packetId relay rewrite kar sakta hai. Ciphertext = AES-GCM authenticated.

16. **`@Valid` kyun zaroori hai?** → Constraints define karte hain annotations, enforce karta hai @Valid. Bina iske null bypass hota.

17. **Constructor injection field injection se better?** → Immutability (final), testability (no Spring context), Spring recommendation.

18. **ADR kya hota hai?** → Architecture Decision Record — why this choice, what was rejected, consequences. Shows engineering maturity.

19. **`@Profile("!prod")` seed data pe?** → Production mein alice/bob fake accounts nahi chahiye. Profile ensure karta hai.

20. **`Numeric(19,2)` balance kyun, double nahi?** → Floating point = precision errors in money. 0.1 + 0.2 ≠ 0.3 in IEEE 754.

21. **GlobalExceptionHandler mein InsufficientFunds 422 kyun?** → 400 = bad request. 422 = valid request, business rule failed.

22. **`Account.createdAt` `updatable=false` kyun?** → Registration timestamp = immutable fact. JPA prevents accidental overwrite.

23. **`ApplicationReadyEvent` vs `@PostConstruct` for banner?** → `@PostConstruct` = bean init time, server not ready. `ApplicationReadyEvent` = server accepting requests.

24. **Circuit breaker fallback sentinel pattern?** → Return stub with CIRCUIT_OPEN status instead of throwing — pipeline continues gracefully.

25. **Defense in depth kaisa implement kiya?** →
```
L1: JWT auth (unauthorized bridge blocked)
L2: Idempotency cache (ConcurrentHashMap)
L3: AES-GCM tag (tamper detection)
L4: Freshness check (replay protection)
L5: Circuit breaker (DB failure isolation)
L6: @Retry (transient DB errors)
L7: DB UNIQUE constraint (distributed fallback)
L8: @Version optimistic lock (balance consistency)
```

---

## 🏗️ Package Structure

```
com.demo.upimesh/
├── config/
│   ├── WebSocketConfig.java      ← STOMP broker + SockJS endpoint
│   ├── JwtAuthFilter.java        ← OncePerRequestFilter for /api/bridge/ingest
│   ├── SecurityHeadersFilter.java
│   └── AppConfig.java
├── controller/
│   ├── ApiController.java        ← All REST endpoints + /bridge/register
│   ├── DashboardController.java
│   └── HealthController.java
├── service/
│   ├── BridgeIngestionService.java ← Main ingestion pipeline
│   ├── SettlementService.java    ← @Retry + @CircuitBreaker + @Transactional
│   ├── MeshSimulatorService.java ← Gossip engine
│   ├── MeshEventPublisher.java   ← WebSocket event publisher
│   ├── MeshMetricsService.java   ← Micrometer counters + timer
│   ├── JwtService.java           ← HS256 token issuance + validation
│   ├── IdempotencyService.java   ← Interface (DIP)
│   └── LocalIdempotencyService.java ← ConcurrentHashMap impl
├── crypto/
│   ├── HybridCryptoService.java  ← RSA-OAEP + AES-256-GCM
│   └── ServerKeyHolder.java      ← RSA keypair at startup
└── model/
    ├── Account.java              ← @Version optimistic lock
    ├── Transaction.java          ← Status: SETTLED|REJECTED|CIRCUIT_OPEN
    ├── MeshPacket.java
    └── PaymentInstruction.java
```
