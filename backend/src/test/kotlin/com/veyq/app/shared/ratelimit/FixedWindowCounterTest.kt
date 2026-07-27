package com.veyq.app.shared.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The imperative shell around [RateLimitWindow]: per-key isolation, the strict over-limit boundary, the
 * `Retry-After` it hands back, and the window rolling over. The clock is a parameter here, so these are pure
 * unit tests — the Quarkus-booted siblings (`AuthRateLimitTest`) prove the *wiring*, not the arithmetic.
 */
class FixedWindowCounterTest {

    private val max = 3L
    private val window = 60L

    @Test
    fun theRequestPastTheAllowanceIsTheFirstOneRejected() {
        val counter = FixedWindowCounter()
        val now = 1_000L
        repeat(3) { assertNull(counter.consume("k", now, max, window), "within the allowance") }
        assertNotNull(counter.consume("k", now, max, window), "the 4th request is over the limit of 3")
    }

    @Test
    fun oneKeyExhaustingItsBudgetDoesNotAffectAnother() {
        val counter = FixedWindowCounter()
        val now = 1_000L
        repeat(4) { counter.consume("noisy", now, max, window) }
        assertNotNull(counter.consume("noisy", now, max, window), "the noisy key stays throttled")
        assertNull(counter.consume("quiet", now, max, window), "a different key has its own budget")
    }

    @Test
    fun theRetryAfterIsTheTimeLeftInTheCurrentWindow() {
        val counter = FixedWindowCounter()
        val now = 1_020L // window [1_020, 1_080) for a 60s window aligned on the epoch
        repeat(3) { counter.consume("k", now, max, window) }
        assertEquals(60L, counter.consume("k", now, max, window), "a whole window remains at its start")
        assertEquals(45L, counter.consume("k", now + 15, max, window), "15s in, 45s remain")
    }

    @Test
    fun theBudgetIsRestoredWhenTheWindowRollsOver() {
        val counter = FixedWindowCounter()
        val now = 1_020L
        repeat(4) { counter.consume("k", now, max, window) }
        assertNotNull(counter.consume("k", now, max, window), "still throttled inside the window")
        assertNull(counter.consume("k", now + window, max, window), "the next window starts a fresh count")
    }

    @Test
    fun anElapsedBucketIsEvictedOnceTheMapGrowsPastItsBound() {
        // maxTrackedKeys = 1, so the second distinct key triggers the sweep of already-elapsed windows.
        val counter = FixedWindowCounter(maxTrackedKeys = 1)
        val now = 1_020L
        repeat(4) { counter.consume("stale", now, max, window) }
        assertNotNull(counter.consume("stale", now, max, window), "throttled while its window is live")

        // Two later keys: the first pushes the map past the bound, the second triggers the eviction pass that
        // drops "stale" — whose window has by then elapsed. Its budget is therefore fresh again.
        counter.consume("a", now + window, max, window)
        counter.consume("b", now + window, max, window)
        assertNull(counter.consume("stale", now + window, max, window), "the evicted key starts over")
    }
}
