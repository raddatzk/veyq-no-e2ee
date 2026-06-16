package com.veyq.app.shared.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun allowsUpToTheLimitThenBlocksWithinTheWindow() {
        val limiter = RateLimiter()
        repeat(3) { assertTrue(limiter.tryAcquire("k", limit = 3, windowMs = 60_000), "hit ${it + 1} within limit") }
        assertFalse(limiter.tryAcquire("k", limit = 3, windowMs = 60_000), "4th hit is blocked")
    }

    @Test
    fun keysAreIndependent() {
        val limiter = RateLimiter()
        assertTrue(limiter.tryAcquire("a", 1, 60_000))
        assertFalse(limiter.tryAcquire("a", 1, 60_000))
        assertTrue(limiter.tryAcquire("b", 1, 60_000), "a different key has its own budget")
    }

    @Test
    fun theWindowResetsAfterItElapses() {
        val limiter = RateLimiter()
        // a 0 ms window means every call is a fresh window → never blocks
        repeat(5) { assertTrue(limiter.tryAcquire("k", limit = 1, windowMs = 0)) }
    }
}
