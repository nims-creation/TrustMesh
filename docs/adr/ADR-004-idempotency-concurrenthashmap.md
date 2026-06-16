# ADR-004: ConcurrentHashMap.putIfAbsent() for In-Process Idempotency

**Date:** 2024-01-15  
**Status:** Accepted  
**Deciders:** TrustMesh Core Team

---

## Context

In TrustMesh, multiple bridge nodes can upload the **identical encrypted packet** to `/api/bridge/ingest` within milliseconds of each other (e.g., three phones walk outside with 4G simultaneously). The server must ensure exactly one settlement occurs.

This is the **idempotency problem**: `f(f(x)) = f(x)` — calling the operation N times must have the same effect as calling it once.

We need an **atomic gate** that:
1. Is checked before any DB write
2. Guarantees that only one thread "wins" even under parallel execution
3. Is fast enough to not bottleneck the settlement pipeline

---

## Decision

Use `ConcurrentHashMap.putIfAbsent(key, value)` as the primary idempotency gate (Layer 1).

```java
// IdempotencyService implementation
private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();

public boolean claim(String packetHash) {
    Instant prev = seen.putIfAbsent(packetHash, Instant.now());
    return prev == null;  // true = first arrival (proceed), false = duplicate (drop)
}
```

Key = `SHA-256(ciphertext)` (not `packetId` — relay nodes can rewrite outer fields).

---

## Why putIfAbsent is Atomic

`ConcurrentHashMap.putIfAbsent` is a single atomic operation:
- Internally uses compare-and-swap (CAS) at the bucket level
- Guarantees that among N concurrent threads calling `putIfAbsent(hash, ...)`, **exactly one** receives `null` back (the winner)
- No `synchronized` block needed — lock-free for the common (non-collision) case

This is fundamentally different from:
```java
// WRONG — NOT atomic, race condition!
if (!map.containsKey(hash)) {   // Thread A checks: not present
    map.put(hash, now);          // Thread B also checks: not present
}                                // Both proceed — double settlement!
```

---

## Rejected Alternatives

### Option A: `synchronized` HashMap
**Rejected because:**
- Entire map is locked for every read and write — serializes all requests
- `putIfAbsent` on `ConcurrentHashMap` is significantly faster under contention (CAS vs mutex)

### Option B: DB `UNIQUE` constraint alone (without in-memory gate)
**Rejected because:**
- Every duplicate would hit the DB, attempt an INSERT, fail with `ConstraintViolationException`
- DB exceptions are expensive (transaction rollback, connection overhead)
- Under a flood of duplicates, the DB becomes a bottleneck
- We want duplicates to be rejected **before they touch the DB**

The DB `UNIQUE` constraint on `packet_hash` is kept as **Layer 2** (safety net for horizontal scaling across multiple JVM instances), but it is not the primary gate.

### Option C: Redis `SET NX EX`
**Best for production** — see ADR-005.  
**Not used in dev/test** because it requires an external Redis process, which breaks the "zero dependencies, clone-and-run" demo experience.

---

## Idempotency Key Choice: SHA-256(ciphertext)

**Why not `packetId`?**
- `packetId` is an outer field of `MeshPacket` — a relay node can rewrite it trivially
- Two different `packetId` values could carry byte-identical ciphertexts (same payment, relayed by two phones)
- We want to deduplicate on **content**, not on a mutable identifier

**Why SHA-256 of ciphertext bytes (not Base64 string)?**
- Base64 string comparison is charset-dependent — two Base64 representations of the same bytes could differ in whitespace or padding
- SHA-256 of the raw decoded bytes is canonical and deterministic
- This was an actual bug in the v0.1 implementation — fixed in v1.0

---

## Consequences

✅ **Positive:**
- O(1) average-case lookup and insert
- Lock-free for the common case (no collision)
- Prevents any DB write for duplicates — maximum efficiency
- Proven by `IdempotencyConcurrencyTest` (10 threads, same packet, exactly 1 settles)

⚠️ **Negative/Trade-offs:**
- In-memory only — does not survive JVM restart (acceptable for demo; use Redis in production)
- Single-instance only — does not coordinate across multiple app replicas (see ADR-005)
- Memory grows unbounded without eviction — mitigated by TTL-based eviction scheduled task

---

## References
- [Java Docs — ConcurrentHashMap.putIfAbsent](https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#putIfAbsent(K,V))
- [Doug Lea — Java Concurrency in Practice, Ch. 5](https://jcip.net/)
- [Redis SET NX — distributed equivalent](https://redis.io/commands/set/)
