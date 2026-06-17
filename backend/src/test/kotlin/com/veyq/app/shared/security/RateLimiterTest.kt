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

    @Test
    fun byteBudgetTripsOnlyAfterTheBudgetIsExceeded() {
        val limiter = RateLimiter()
        val budget = 1000L
        assertFalse(limiter.isOverByteBudget("ip", budget, 60_000), "fresh key is under budget")
        limiter.recordBytes("ip", 600, 60_000)
        assertFalse(limiter.isOverByteBudget("ip", budget, 60_000), "600 of 1000 is still under budget")
        limiter.recordBytes("ip", 500, 60_000) // total 1100 > 1000
        assertTrue(limiter.isOverByteBudget("ip", budget, 60_000), "1100 exceeds the 1000 budget")
    }

    @Test
    fun byteBudgetKeysAreIndependentAndResetWithTheWindow() {
        val limiter = RateLimiter()
        limiter.recordBytes("a", 2000, 60_000)
        assertTrue(limiter.isOverByteBudget("a", 1000, 60_000))
        assertFalse(limiter.isOverByteBudget("b", 1000, 60_000), "a different IP has its own egress budget")
        // a 0 ms window means the accumulator is always fresh → never over budget
        limiter.recordBytes("c", 5000, 0)
        assertFalse(limiter.isOverByteBudget("c", 1000, 0))
    }

    @Test
    fun nonPositiveByteCountsAreIgnored() {
        val limiter = RateLimiter()
        limiter.recordBytes("ip", -5, 60_000)
        limiter.recordBytes("ip", 0, 60_000)
        assertFalse(limiter.isOverByteBudget("ip", 0, 60_000), "no positive bytes recorded → not over a zero budget")
    }
}
