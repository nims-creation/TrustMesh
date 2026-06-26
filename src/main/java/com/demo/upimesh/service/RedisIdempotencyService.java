package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Production implementation of IdempotencyService using Redis.
 *
 * Uses Redis SETNX (setIfAbsent) for distributed, atomic locking.
 * This guarantees that even if 10,000 users send the exact same packet to
 * 50 different load-balanced servers at the exact same millisecond,
 * only ONE server will return true, and the rest will drop the duplicate.
 */
@Service
@Profile("prod")
public class RedisIdempotencyService implements IdempotencyService {

    private final StringRedisTemplate redisTemplate;

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean claim(String packetHash) {
        String key = "idemp:" + packetHash;
        
        // setIfAbsent is the Redis SETNX command. It is atomic.
        // It returns true if the key was set (meaning we are the first to claim it),
        // and false if the key already existed (duplicate packet).
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, String.valueOf(System.currentTimeMillis()), Duration.ofSeconds(ttlSeconds));
                
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public int size() {
        // Counting all keys in Redis (KEYS *) is an O(N) operation and blocks Redis.
        // Since size() is only used by the development dashboard, we return 0 in production.
        return 0;
    }

    @Override
    public void clear() {
        // Clearing all keys is also expensive and dangerous in production (FLUSHDB).
        // This is primarily for the local /api/mesh/reset endpoint which shouldn't wipe prod.
    }
}
