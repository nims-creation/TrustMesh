package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local (single-JVM) implementation of IdempotencyService.
 *
 * Uses ConcurrentHashMap.putIfAbsent — JVM-local equivalent of Redis SETNX.
 *
 * @Profile("!prod") means this bean is loaded in every profile EXCEPT prod.
 * In production, drop in RedisIdempotencyService @Profile("prod") and this
 * class is automatically replaced — no other code changes needed.
 *
 * Why ConcurrentHashMap is enough for demo:
 *   - Single node, no distributed coordination needed.
 *   - putIfAbsent is a single atomic CAS operation — 100 concurrent threads
 *     calling claim(hash) will result in exactly one returning true.
 *
 * What changes in production:
 *   Redis: SET <packetHash> <timestamp> NX PX <ttlMillis>
 *   NX = only set if Not eXists → same "first wins" semantics
 *   PX = set expiry in ms       → TTL eviction handled by Redis natively
 */
@Service
@Profile("!prod")
public class LocalIdempotencyService implements IdempotencyService {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public boolean claim(String packetHash) {
        Instant now = Instant.now();
        Instant prev = seen.putIfAbsent(packetHash, now);
        return prev == null;  // true = first claimant; false = duplicate
    }

    @Override
    public int size() {
        return seen.size();
    }

    /**
     * Periodically evict entries past their TTL.
     * In Redis this is free (TTL is set at write time).
     * Here we do it manually every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    @Override
    public void clear() {
        seen.clear();
    }
}
