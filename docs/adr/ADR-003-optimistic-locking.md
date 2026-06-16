# ADR-003: Optimistic Locking (@Version) over Pessimistic Locking

**Date:** 2024-01-15  
**Status:** Accepted  
**Deciders:** TrustMesh Core Team

---

## Context

When a bridge node submits a payment packet, the settlement engine must:
1. Read the sender's current balance
2. Verify balance >= amount
3. Debit sender, credit receiver
4. Persist the transaction record

In TrustMesh's concurrency scenario, multiple bridge nodes can submit the **same packet** simultaneously (or different packets for the **same sender account**). Without a concurrency control mechanism, two concurrent settlements on the same account can cause a lost update (double-debit or incorrect balance).

We need a strategy to protect `Account.balance` against concurrent writes.

---

## Decision

Use **Optimistic Locking** via JPA's `@Version` annotation on the `Account` entity.

```java
@Entity
public class Account {
    @Version
    private Long version;  // Auto-incremented on every UPDATE
    
    private BigDecimal balance;
}
```

Hibernate automatically adds `WHERE version = :expected_version` to every UPDATE query. If another transaction committed first (version mismatch), Hibernate throws `OptimisticLockException` — which Spring's `@Transactional` rolls back cleanly.

---

## Rejected Alternatives

### Option A: Pessimistic Locking (`SELECT FOR UPDATE`)
**Rejected because:**
- `SELECT FOR UPDATE` holds a DB-level exclusive row lock for the entire transaction duration
- Under concurrent load, threads queue behind each other — serializes all balance updates
- Dramatically reduces throughput on high-traffic accounts (e.g., a merchant VPA receiving many payments)
- Risk of deadlock if two transactions lock rows in different order
- Lock held even if the eventual outcome is a duplicate drop (unnecessary contention)

### Option B: `synchronized` Java block
**Rejected because:**
- JVM-level lock — useless across multiple application instances (horizontal scaling)
- Doesn't survive app restarts
- Not a database-level guarantee

### Option C: No concurrency control
**Rejected because:**
- Classic lost update problem: Thread A reads balance=₹1000, Thread B reads balance=₹1000, both debit ₹500, both write ₹500. Net result: ₹500 deducted instead of ₹1000. Financial data corruption.

---

## Why Optimistic is Better for This Workload

Optimistic locking is optimal when **read-heavy, write-light, and conflicts are rare**:

| Scenario | Optimistic | Pessimistic |
|---|---|---|
| Low conflict (99% of packets) | ✅ No lock overhead | ❌ Lock acquired unnecessarily |
| High conflict (duplicate bridge flood) | ✅ First writer wins, others retry | ❌ All threads queue, one at a time |
| Multi-instance horizontal scaling | ✅ DB-level — works across JVMs | ✅ DB-level — also works |
| Read throughput | ✅ Reads never blocked | ❌ Reads blocked by write locks |

In TrustMesh, idempotency filtering (Layer 1) removes duplicates **before** they reach the settlement layer — so actual concurrent writes to the same account are extremely rare. Optimistic locking is the clear winner.

---

## Consequences

✅ **Positive:**
- Zero lock contention on reads (99%+ of operations)
- Scales horizontally — `@Version` is a DB column, works across multiple app instances
- No deadlock risk
- Spring handles `OptimisticLockException` → `@Transactional` rollback automatically

⚠️ **Negative/Trade-offs:**
- On a genuine conflict (rare), the losing transaction throws and must be retried by the caller
- Slightly more complex error handling required (catch `OptimisticLockException`)
- If conflict rate is very high (e.g., a viral merchant), retry storms are possible — mitigated by exponential backoff

---

## References
- [Martin Fowler — Optimistic Offline Lock](https://martinfowler.com/eaaCatalog/optimisticOfflineLock.html)
- [JPA 2.2 Specification — Section 3.4.2 Optimistic Locking](https://javaee.github.io/jpa-spec/index.html)
- [Hibernate ORM — Locking](https://docs.jboss.org/hibernate/orm/6.5/userguide/html_single/Hibernate_User_Guide.html#locking)
