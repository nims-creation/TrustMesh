# TrustMesh — Interview Notes
> **Padh le bhai, interview mein kaam aayega! 🎯**
> Har phase ke baad yeh file update hoti rahegi.

---

## 🚀 Project Overview

**Kya banaya?**
Ek Spring Boot backend jo offline UPI payments ko simulate karta hai — bina internet ke, Bluetooth mesh ke through.

**Core Idea:**
- Tum basement mein ho, internet nahi hai
- Payment encrypt karke nearby phones pe bhejte ho (Bluetooth gossip)
- Koi bhi phone bahar jaake internet pakad le, payment upload kar deta hai
- Backend decrypt, deduplicate aur settle karta hai

**3 Hard Problems jo solve kiye:**
1. Untrusted phone ne packet carry kiya — kaise ensure karein ki tamper na ho? → **Hybrid Encryption**
2. 3 bridge nodes same payment leke aaye ek saath — 3x debit ho jaata? → **Atomic Idempotency**
3. Koi purana packet replay kar de — → **Freshness Window + Nonce**

---

## 📦 Tech Stack & Kyun Choose Kiya

| Technology | Version | Kyun? |
|---|---|---|
| **Spring Boot** | 3.4.x | Industry standard, auto-config, embedded Tomcat |
| **Java** | 17 | LTS version, Records support, modern syntax |
| **H2 (Dev) / PostgreSQL (Prod)** | - | H2 = zero setup demo, Postgres = production grade |
| **Spring Data JPA** | - | Repository pattern, boilerplate reduce |
| **Hibernate** | - | ORM, optimistic locking (@Version) |
| **Jackson** | 2.x | JSON serialize/deserialize |
| **Lombok** | 1.18.x | Boilerplate reduce — @Data, @Builder |
| **Spring Validation** | - | @Valid, @NotBlank, @Positive |
| **Spring Security** | 6.x | Auth, endpoint protection |
| **Thymeleaf** | - | Server-side HTML templating for dashboard |

---

## 🔐 Cryptography — Interview ke liye IMPORTANT

### Hybrid Encryption (RSA-OAEP + AES-256-GCM)

**Q: RSA directly use kyun nahi kiya?**
RSA-2048 sirf ~245 bytes encrypt kar sakta hai. Hamara JSON payload usse bada ho sakta hai. Solution = Hybrid.

**How it works (step by step):**
```
1. Ek fresh AES-256 key generate karo (is packet ke liye sirf)
2. Payment JSON ko AES-256-GCM se encrypt karo  ← fast + authenticated
3. Sirf AES key ko RSA-OAEP se encrypt karo      ← server ki public key se
4. Pack karo: [256 bytes RSA-encrypted AES key][12 bytes IV][AES ciphertext + 16-byte GCM tag]
5. Base64 encode karke bhejo
```

**Q: AES-GCM kyun, sirf AES kyun nahi?**
GCM = **Authenticated Encryption**. Ek bhi bit flip ho ciphertext mein, decryption exception throw karta hai. 
- AES-CBC sirf encrypt karta hai, integrity nahi check karta
- GCM encrypt + authenticate dono karta hai (same as TLS!)

**Q: Public key phone pe kaise aata hai?**
Phone pehle kabhi online tha tab `/api/server-key` se public key cache kiya tha.

### PIN Hashing — BCrypt kyun?

**Q: SHA-256 se kya problem thi?**
- SHA-256 fast hai — attacker second mein billions hashes try kar sakta hai
- No salt — rainbow table attack possible
- BCrypt slow hai by design (work factor), salted by default

```java
// WRONG — SHA-256 no salt
sha256.digest(pin.getBytes())

// RIGHT — BCrypt with strength 10
new BCryptPasswordEncoder(10).encode(pin)
```

---

## ⚛️ Idempotency — Interview ka Star Topic

**Q: Idempotency kya hota hai?**
Same operation N baar karo, result wahi rahe. `f(f(x)) = f(x)`

**Q: Yahan problem kya thi?**
3 bridge nodes same packet carry kar rahe hain. Teeno bahar nikalte hain ek saath. Teeno `/api/bridge/ingest` pe POST karte hain milliseconds mein. Agar naively process karo → sender ka ₹1500 debit, ₹500 ki jagah.

**Solution: `ConcurrentHashMap.putIfAbsent()` — Atomic Compare-and-Set**
```java
Instant prev = seen.putIfAbsent(packetHash, now);
return prev == null; // true = pehli baar, false = duplicate
```

**Q: `putIfAbsent` atomic kyun hai?**
ConcurrentHashMap ka internal locking ensure karta hai — 100 threads ek saath call karein, sirf ek ko `null` milega. Baaki sab ko existing entry.

**Q: Hash ciphertext ka kyun, packetId ka kyun nahi?**
- `packetId` ek malicious node rewrite kar sakta hai
- Cleartext hash ke liye pehle decrypt karna padega — RSA expensive hai
- Ciphertext = AES-GCM authenticated. Tamper = exception on decrypt. Same packet = byte-identical ciphertext = same hash ✅

**Production mein kya hoga?**
`ConcurrentHashMap` → **Redis `SET key NX EX 86400`**. Same semantics, distributed.

---

## 🔒 Optimistic Locking — @Version

**Q: @Version kya karta hai?**
```java
@Version
private Long version; // Account.java mein
```
JPA automatically version column check karta hai update pe. Agar 2 transactions same account ko ek saath update karein:
- Thread 1 reads version=5, Thread 2 reads version=5
- Thread 1 saves → version becomes 6
- Thread 2 save try karta hai version=5 se → `OptimisticLockException` ❌

**Q: Pessimistic locking se better kyun?**
Pessimistic = DB row lock → contention, deadlock risk, slow.
Optimistic = no lock → concurrent reads, conflict sirf tabhi handle karo jab ho (which is rare).

---

## 🌐 REST API Design

### Endpoints ki categories:
```
/api/server-key          → Public key exchange
/api/demo/send           → Sender phone simulate karo
/api/mesh/gossip         → Ek gossip round
/api/mesh/flush          → Bridges upload karo
/api/mesh/reset          → Demo reset
/api/bridge/ingest       → THE production endpoint
/api/accounts            → Balance check
/api/transactions        → Ledger
```

**Q: `/api/bridge/ingest` pe X-Bridge-Node-Id header kyun?**
Audit trail ke liye — kaunsa bridge node ne packet deliver kiya, record rakho.

### @Valid — Bean Validation

```java
public ResponseEntity<?> ingest(@RequestBody @Valid MeshPacket packet, ...) {
    // @NotBlank, @Min constraints are now enforced
}
```

**Q: @Valid na hota toh?**
Constraints annotations toh hain (`@NotBlank`, `@Min`) lekin Spring unhe enforce tab karta hai jab `@Valid` ya `@Validated` laga ho controller parameter pe. Bina `@Valid` ke null ciphertext directly crypto service tak pahunch jaata.

---

## 🏗️ Architecture Decisions

### Layered Architecture

```
model/          ← Domain: JPA entities, repositories
crypto/         ← Cryptography: HybridCryptoService, ServerKeyHolder
service/        ← Business logic: BridgeIngestionService, SettlementService
controller/     ← HTTP: ApiController, DashboardController
config/         ← Configuration: AppConfig (@EnableScheduling)
```

**Q: Kyun alag layers?**
- Single Responsibility — har layer ka ek kaam
- Testability — service layer Spring context ke bina test ho sakta hai
- Maintainability — crypto badlna hai toh sirf crypto/ touch karo

### Constructor Injection vs Field Injection

```java
// Field injection (purana, bura)
@Autowired private HybridCryptoService crypto;

// Constructor injection (sahi)
private final HybridCryptoService crypto;
public BridgeIngestionService(HybridCryptoService crypto) {
    this.crypto = crypto;
}
```

**Q: Constructor injection better kyun?**
- `final` field → immutable → thread-safe
- Unit test mein Spring context chahiye nahi, directly inject karo
- Spring bhi internally constructor injection recommend karta hai

---

## 🧪 Testing Strategy

### 3 Test Cases Jo Likhey:

**1. `encryptDecryptRoundTrip`**
- Encrypt karo, decrypt karo, same data milna chahiye
- Tests: HybridCryptoService ki correctness

**2. `tamperedCiphertextIsRejected`**
- Ciphertext mein ek byte flip karo
- Expected: `INVALID` outcome, not crash
- Tests: AES-GCM authentication tag verification

**3. `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce`**
- 3 threads, ek packet, simultaneous delivery
- Expected: 1 SETTLED, 2 DUPLICATE_DROPPED, balance ek hi baar change
- Tests: Idempotency under concurrency

**Q: CountDownLatch kyun use karna chahiye tha?**
Bina latch ke threads sequentially run ho sakte hain CI mein. Latch ensure karta hai:
```java
CountDownLatch go = new CountDownLatch(1);
// sabhi threads ready ho jaate hain
go.countDown(); // teeno ek saath start
```

---

## 🛡️ Security Decisions

### Replay Attack Protection — 2 layers:

1. **`signedAt` timestamp** — encrypted payload ke andar. Server 24h se purana reject karta hai. Attacker `signedAt` change nahi kar sakta (GCM tag toot jaayega).

2. **`nonce` (UUID)** — Alice ne Bob ko ₹100 bheja 2 baar. Dono ki nonce alag → ciphertext alag → hash alag → dono settle. Lekin purane packet ki replay? Byte-identical → same hash → idempotency cache catch karta hai.

### Defense in Depth (Multiple layers):

```
Layer 1: Idempotency cache (ConcurrentHashMap / Redis)
Layer 2: AES-GCM authentication tag (tamper detection)
Layer 3: Freshness check (replay protection)
Layer 4: DB unique index on packetHash (last resort)
Layer 5: @Version optimistic lock (concurrent balance update)
```

---

## 📈 What Would Change in Production

| Demo mein | Production mein | Kyun? |
|---|---|---|
| H2 in-memory | PostgreSQL | Persistence, ACID guarantees |
| ConcurrentHashMap | Redis SETNX | Distributed instances |
| RSA keypair on startup | AWS KMS / Vault | Private key never leaves HSM |
| No auth | Mutual TLS | Bridge node verification |
| No rate limit | Bucket4j / API Gateway | DDoS protection |
| Console logging | Structured logs + SIEM | Alert on INVALID spike |
| sha256(PIN) | BCrypt | Brute-force resistance |

---

## 🔄 Phase-wise Updates Done

### Phase 1 — Critical Bug Fixes _(aane wala hai)_
- [ ] `@Valid` added to `/api/bridge/ingest`
- [ ] `hashCiphertext` raw bytes use karta hai
- [ ] DemoSendRequest validation added
- [ ] H2 console prod profile mein disabled
- [ ] dave@demo sender dropdown mein add kiya

### Phase 2 — Code Quality _(aane wala hai)_
- [ ] Lombok added (`@Data`, `@Builder`, `@RequiredArgsConstructor`)
- [ ] Constructor injection everywhere
- [ ] Custom `InsufficientFundsException`
- [ ] Dynamic account dropdowns in dashboard

### Phase 3 — Testing ✅ DONE — 11/11 Tests Passing
- [x] FreshnessCheckTest — stale packet (>24h), fresh (1h), future-dated rejected
- [x] InsufficientFundsTest — INVALID + no balance change on rejection
- [x] MeshAndCryptoTest — reset clears state, hash deterministic, unknown VPA handled
- [x] Existing: encryptDecryptRoundTrip, tamperedCiphertextIsRejected, singlePacketDeliveredByThreeBridgesSettlesExactlyOnce

### Phase 4 — Security _(aane wala hai)_
- [ ] BCrypt for PIN hashing
- [ ] Spring Security basic setup
- [ ] Spring Boot upgraded to 3.4.x

### Phase 5 — Production Readiness _(aane wala hai)_
- [ ] PostgreSQL profile
- [ ] Flyway migrations
- [ ] Redis idempotency
- [ ] Rate limiting

---

## 💡 Interview Mein Pooche Jaane Wale Questions

1. **Hybrid encryption kyun use kiya, sirf RSA kyun nahi?**
   → RSA size limit hai ~245 bytes. Hybrid pattern TLS/PGP/Signal sab use karte hain.

2. **Idempotency kaise guarantee karte ho concurrent requests pe?**
   → `ConcurrentHashMap.putIfAbsent` atomic hai. One winner, rest duplicates.

3. **Optimistic vs Pessimistic locking kab use karte ho?**
   → Optimistic = read heavy, low contention. Pessimistic = write heavy, high contention.

4. **AES-GCM specifically kyun?**
   → Authenticated encryption — tampering detect hota hai. Plain AES-CBC sirf encrypt karta hai.

5. **Replay attack kaise rokoge?**
   → signedAt (freshness) + nonce (uniqueness) + idempotency cache.

6. **@Valid kyun zaroori hai?**
   → Annotations constraints define karte hain, @Valid unhe enforce karta hai. Bina iske null bypass ho sakta hai.

7. **Constructor injection field injection se better kyun?**
   → Immutability, testability (no Spring context needed), Spring recommendation.
