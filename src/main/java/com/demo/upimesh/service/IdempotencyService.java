package com.demo.upimesh.service;

/**
 * Contract for the idempotency layer.
 *
 * Why an interface?
 * -----------------
 * The DEMO implementation (LocalIdempotencyService) uses a ConcurrentHashMap —
 * fast, simple, but single-JVM only. A multi-node production deployment needs
 * a distributed implementation backed by Redis.
 *
 * With this interface, swapping the implementation for production is one step:
 *   1. Add spring-boot-starter-data-redis to pom.xml
 *   2. Create RedisIdempotencyService implements IdempotencyService
 *   3. Annotate the new class with @Profile("prod") and
 *      LocalIdempotencyService with @Profile("!prod")
 *   4. The rest of the codebase (BridgeIngestionService, ApiController,
 *      the test suite) never changes — they depend on this interface.
 *
 * This is the Dependency Inversion Principle (DIP) from SOLID:
 * high-level policy (BridgeIngestionService) depends on an abstraction,
 * not a concrete detail (ConcurrentHashMap vs. Redis).
 */
public interface IdempotencyService {

    /**
     * Atomically claim a packet hash.
     *
     * @return true  if this caller is the FIRST to claim this hash (process it)
     *         false if the hash was already claimed (duplicate — drop it)
     *
     * In Redis: equivalent to SET key value NX PX ttlMillis
     * In JVM:   equivalent to ConcurrentHashMap.putIfAbsent(key, now) == null
     */
    boolean claim(String packetHash);

    /** Returns the current number of tracked hashes (useful for the dashboard). */
    int size();

    /** Clears all tracked hashes. Used by /api/mesh/reset and tests. */
    void clear();
}
