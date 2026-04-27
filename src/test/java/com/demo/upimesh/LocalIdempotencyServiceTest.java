package com.demo.upimesh;

import com.demo.upimesh.service.LocalIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for LocalIdempotencyService to verify atomic claims
 * and TTL eviction logic.
 */
public class LocalIdempotencyServiceTest {

    private LocalIdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new LocalIdempotencyService();
        // Set TTL to 0 for immediate expiry in the eviction test
        ReflectionTestUtils.setField(idempotencyService, "ttlSeconds", 0L);
    }

    @Test
    void testClaimIsAtomicAndRejectsDuplicates() {
        String hash = "test-hash-123";

        // First claim should succeed
        assertTrue(idempotencyService.claim(hash));

        // Second claim should fail
        assertFalse(idempotencyService.claim(hash));
    }

    @Test
    void testEvictionRemovesExpiredEntries() {
        String hash = "test-hash-456";

        // Claim succeeds
        assertTrue(idempotencyService.claim(hash));

        // Because ttlSeconds is 0, evictExpired() should instantly remove it
        idempotencyService.evictExpired();

        // Should be able to claim again after eviction
        assertTrue(idempotencyService.claim(hash));
    }
}
