# ADR-005: Redis SET NX EX for Production Idempotency

**Date:** 2024-01-15  
**Status:** Accepted (deferred to production profile)  
**Deciders:** TrustMesh Core Team

---

## Context

ADR-004 documents `ConcurrentHashMap.putIfAbsent` as the in-process idempotency gate. This works perfectly for a single JVM instance but has two production limitations:

1. **Single-instance only** — If two app instances receive the same packet simultaneously, each has its own `ConcurrentHashMap` and both will claim the hash as new → double settlement
2. **No persistence** — If the JVM restarts, the cache is lost → a previously-seen packet could be re-processed

In production, TrustMesh would run as a horizontally-scaled cluster (multiple pods behind a load balancer). We need a **distributed idempotency store**.

---

## Decision

Use **Redis `SET key value NX EX seconds`** for production idempotency, wrapped behind the existing `IdempotencyService` interface.

```java
// Production implementation (active when Spring profile = "prod")
@Service
@Profile("prod")
public class RedisIdempotencyService implements IdempotencyService {

    private final StringRedisTemplate redis;
    private final long ttlSeconds;

    @Override
    public boolean claim(String packetHash) {
        // SET packetHash "1" NX EX 86400
        // NX = only set if Not eXists (atomic)
        // EX 86400 = expire after 24 hours (UPI's offline transaction window)
        Boolean wasAbsent = redis.opsForValue()
            .setIfAbsent(packetHash, "1", Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(wasAbsent);
    }
}
```

The `ConcurrentHashMap` implementation is annotated `@Profile("!prod")` — it runs in dev and test where Redis is not available.

---

## Why Redis SET NX is Atomic

Redis is **single-threaded for command execution**. `SET key value NX EX ttl` is processed atomically by the Redis event loop:
- Two concurrent requests arrive: they are queued in Redis's single-threaded command processor
- First request: key absent → SET succeeds → returns OK
- Second request: key present → NX condition fails → returns nil
- Guaranteed: only one caller gets OK, regardless of how many app instances are involved

This is the **distributed equivalent** of `ConcurrentHashMap.putIfAbsent` — same semantics, different scope.

---

## Interface-Driven Swap (Dependency Inversion Principle)

The architecture uses the **Dependency Inversion Principle** to make the Redis swap transparent:

```
BridgeIngestionService
    │ depends on (interface)
    ▼
IdempotencyService          ← thin interface: claim(hash), clear(), size()
    ├── LocalIdempotencyService  (@Profile("!prod")) ← ConcurrentHashMap
    └── RedisIdempotencyService  (@Profile("prod"))  ← Redis SET NX
```

`BridgeIngestionService` never knows which implementation it's talking to. Switching from ConcurrentHashMap to Redis requires:
- Adding Redis dependency to `pom.xml`
- Creating `RedisIdempotencyService.java` (one class)
- Setting `SPRING_PROFILES_ACTIVE=prod`
- Zero changes to `BridgeIngestionService` or any other existing class

This is the **Open/Closed Principle** in action: open for extension (new IdempotencyService impl), closed for modification (existing code untouched).

---

## Rejected Alternatives

### Option A: PostgreSQL as idempotency store
**Rejected because:**
- DB write for every ingest request (even duplicates that should be dropped instantly)
- DB latency (5–20ms) vs Redis latency (<1ms)
- We already have DB `UNIQUE` constraint as Layer 2 safety net — adding another DB check is redundant

### Option B: Sticky sessions (route same bridge node to same app instance)
**Rejected because:**
- A load balancer can't know which app instance previously saw a packet hash
- Adds infrastructure complexity (L7 load balancer, session affinity config)
- Doesn't solve the problem if an instance restarts

### Option C: Distributed lock (Redlock)
**Rejected because:**
- Redlock is for mutual exclusion (one process at a time), not for idempotency (cache membership check)
- More complex than SET NX
- Martin Kleppmann has documented reliability concerns with Redlock under network partitions

---

## Production Redis Configuration

```yaml
# application-prod.properties
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.ssl.enabled=${REDIS_SSL:false}

# TTL matches UPI's offline transaction expiry window
upi.mesh.idempotency-ttl-seconds=86400
```

**Recommended Redis deployment:** AWS ElastiCache (Redis 7+) with Multi-AZ replication for high availability. Single-node Redis is sufficient for moderate load (Redis handles 1M+ SET/GET ops/sec).

---

## Consequences

✅ **Positive:**
- Distributed — works across N app instances behind a load balancer
- Persistent — survives individual app restarts (Redis has its own persistence)
- Automatic TTL expiry — 24h entries auto-deleted, no manual eviction needed
- Sub-millisecond latency for claim operations
- Scales to production traffic (100K+ payments/day)

⚠️ **Negative/Trade-offs:**
- Redis is now a required infrastructure dependency in production
- Redis availability becomes part of the SLA — Redis downtime = ingest pipeline cannot safely claim (must fail-closed)
- Network hop to Redis adds ~1ms latency per request (vs nanoseconds for ConcurrentHashMap)
- In-memory cache is lost on Redis restart unless AOF/RDB persistence is configured

---

## References
- [Redis SET command — NX/EX options](https://redis.io/commands/set/)
- [Redis as an Idempotency Store (Stripe Engineering)](https://stripe.com/blog/idempotency)
- [Martin Kleppmann — Redlock critique](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html)
- [DDIA — Chapter 9, Linearizability](https://dataintensive.net/)
