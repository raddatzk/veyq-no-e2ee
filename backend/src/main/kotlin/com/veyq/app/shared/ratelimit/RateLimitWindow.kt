package com.veyq.app.shared.ratelimit

/**
 * The pure fixed-window arithmetic of the rate limiters (ADR 0045 §2), lifted out of the filters so the
 * window/expiry/over-limit boundaries are unit-tested and mutation-verified without a Quarkus boot.
 * [FixedWindowCounter] stays the imperative shell: it reads the clock and drives the `ConcurrentHashMap` /
 * `AtomicLong` plumbing; it asks these functions for every number and boundary it acts on.
 *
 * All times are epoch seconds; `windowSeconds` is the configured window length.
 */
object RateLimitWindow {
    /** The start (inclusive lower bound) of the fixed window `nowSec` falls into — the bucket key's time component. */
    fun windowStart(nowSec: Long, windowSeconds: Long): Long = nowSec - (nowSec % windowSeconds)

    /** Seconds until the current window rolls over — the `Retry-After` a rejected caller must wait. */
    fun retryAfter(nowSec: Long, windowSeconds: Long): Long = windowSeconds - (nowSec % windowSeconds)

    /** Whether a request count has passed the allowance. The count is post-increment, so the boundary is strict. */
    fun isOverLimit(count: Long, maxRequests: Long): Boolean = count > maxRequests

    /** Whether a window opened at `windowStart` has fully elapsed by `nowSec` — the eviction predicate that lets a
     *  stale bucket be dropped when the map grows large. */
    fun hasElapsed(nowSec: Long, windowStart: Long, windowSeconds: Long): Boolean =
        nowSec - windowStart >= windowSeconds
}
