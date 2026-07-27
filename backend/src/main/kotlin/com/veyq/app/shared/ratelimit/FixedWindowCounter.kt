package com.veyq.app.shared.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The in-memory, per-key fixed-window counter shared by the boundary rate-limit filters — the imperative shell
 * around the pure, mutation-verified [RateLimitWindow] arithmetic (ADR 0045 §2). It drives the concurrent map and
 * the clock; it asks [RateLimitWindow] for every boundary it acts on, and re-implements none of them.
 *
 * **Deliberate limitations** (a gateway / distributed bucket is the production answer, see backend CLAUDE.md):
 * counters are **per-instance** (not shared across a horizontally-scaled deployment) and **reset on restart**.
 *
 * The map is bounded: past [maxTrackedKeys] every already-elapsed window is dropped, so a flood of distinct
 * (possibly spoofed) keys cannot grow it without limit.
 */
class FixedWindowCounter(private val maxTrackedKeys: Int = MAX_TRACKED_KEYS) {

    private class Window(val windowStart: Long) { val count = AtomicLong(0) }

    private val buckets = ConcurrentHashMap<String, Window>()

    /**
     * Counts one request against [key] and returns the `Retry-After` seconds if that put it **over**
     * [maxRequests] within [windowSeconds] — or null if it is still allowed.
     */
    fun consume(key: String, nowSec: Long, maxRequests: Long, windowSeconds: Long): Long? {
        val windowStart = RateLimitWindow.windowStart(nowSec, windowSeconds)

        if (buckets.size > maxTrackedKeys) {
            buckets.entries.removeIf { RateLimitWindow.hasElapsed(nowSec, it.value.windowStart, windowSeconds) }
        }

        val window = buckets.compute(key) { _, existing ->
            if (existing != null && existing.windowStart == windowStart) existing else Window(windowStart)
        }!!

        return if (RateLimitWindow.isOverLimit(window.count.incrementAndGet(), maxRequests)) {
            RateLimitWindow.retryAfter(nowSec, windowSeconds)
        } else {
            null
        }
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 100_000
    }
}
