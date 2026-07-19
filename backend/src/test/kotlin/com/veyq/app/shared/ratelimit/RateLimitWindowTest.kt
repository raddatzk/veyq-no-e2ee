package com.veyq.app.shared.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure fixed-window arithmetic (ADR 0045 §2), boot-free and mutation-verified. Every assertion pins an exact
 * number or lands exactly on a boundary — a `%`/`-`/`>=`/`>` mutant that shifts a window edge or an over-limit cut
 * by one must fail here (the assertion-strength trap ADR 0045 exists to close). The HTTP wiring is the boot-level
 * net (AuthRateLimitTest, AuthRateLimitWindowResetTest).
 */
class RateLimitWindowTest {

    private val window = 60L

    @Test
    fun windowStartSnapsDownToTheWindowBoundary() {
        assertEquals(120L, RateLimitWindow.windowStart(120, window), "exactly on the boundary stays put")
        assertEquals(120L, RateLimitWindow.windowStart(121, window), "one second in snaps back to the start")
        assertEquals(120L, RateLimitWindow.windowStart(179, window), "the last second of the window snaps back")
        assertEquals(180L, RateLimitWindow.windowStart(180, window), "the next boundary opens a new window")
    }

    @Test
    fun retryAfterCountsDownToTheNextBoundary() {
        assertEquals(60L, RateLimitWindow.retryAfter(120, window), "at the start a full window remains")
        assertEquals(59L, RateLimitWindow.retryAfter(121, window), "one second in leaves one less")
        assertEquals(1L, RateLimitWindow.retryAfter(179, window), "the last second leaves one to wait")
    }

    @Test
    fun isOverLimitIsStrictlyAboveTheAllowance() {
        assertFalse(RateLimitWindow.isOverLimit(60, 60), "the request that reaches the allowance is still allowed")
        assertTrue(RateLimitWindow.isOverLimit(61, 60), "the request past the allowance is rejected")
    }

    @Test
    fun hasElapsedIsTrueOnlyOnceTheFullWindowHasPassed() {
        assertFalse(RateLimitWindow.hasElapsed(nowSec = 179, windowStart = 120, windowSeconds = window), "still inside the window")
        assertTrue(RateLimitWindow.hasElapsed(nowSec = 180, windowStart = 120, windowSeconds = window), "a full window has elapsed")
        assertTrue(RateLimitWindow.hasElapsed(nowSec = 240, windowStart = 120, windowSeconds = window), "well past the window")
    }
}
