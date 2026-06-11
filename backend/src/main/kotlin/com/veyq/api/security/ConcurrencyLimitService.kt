package com.veyq.api.security

import com.veyq.api.auth.filterchain.RequestContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds the number of concurrently held (in-flight) requests for endpoints that
 * intentionally block the request thread for a long time, such as the public-share
 * media-update long poll. Without such a bound an anonymous caller can open many
 * concurrent long-poll requests and tie up the worker pool until each request's
 * timeout expires, degrading availability for everyone else.
 *
 * Slots are reserved per client (IP) and globally; when either limit is exceeded the
 * caller is rejected immediately with a 429 instead of being parked on a worker thread.
 */
@ApplicationScoped
class ConcurrencyLimitService @Inject constructor(
    private val requestContext: RequestContext,
    private val securityConfig: SecurityConfig,
) {
    // Per-key counts are mutated only inside ConcurrentHashMap.compute, so each
    // acquire/release on a key is atomic: a bucket is created when it first goes
    // positive and removed the moment it returns to zero, with no lost updates and
    // no window where an empty bucket lingers or a non-empty bucket is dropped.
    private val perKeyCounters = ConcurrentHashMap<String, Int>()
    private val totalCounter = AtomicInteger(0)

    fun <T> withPublicShareLongPoll(block: () -> T): T {
        val limits = securityConfig.publicShareUpdates
        val key = "public-share-updates:${clientKey()}"
        acquire(key, limits.maxConcurrentPerIp, limits.maxConcurrentTotal)
        try {
            return block()
        } finally {
            release(key)
        }
    }

    fun clearAll() {
        perKeyCounters.clear()
        totalCounter.set(0)
    }

    private fun acquire(key: String, perKeyLimit: Int, totalLimit: Int) {
        if (!tryAcquirePerKey(key, perKeyLimit)) {
            throw RateLimitExceededException(CONCURRENCY_RETRY_AFTER_SECONDS)
        }
        if (totalCounter.incrementAndGet() > totalLimit) {
            totalCounter.decrementAndGet()
            releasePerKey(key)
            throw RateLimitExceededException(CONCURRENCY_RETRY_AFTER_SECONDS)
        }
    }

    private fun release(key: String) {
        totalCounter.decrementAndGet()
        releasePerKey(key)
    }

    private fun tryAcquirePerKey(key: String, limit: Int): Boolean {
        var acquired = false
        perKeyCounters.compute(key) { _, current ->
            val count = current ?: 0
            if (count >= limit) {
                current
            } else {
                acquired = true
                count + 1
            }
        }
        return acquired
    }

    private fun releasePerKey(key: String) {
        // Returning null removes the bucket; this only happens when the count
        // actually reaches zero within the same atomic compute.
        perKeyCounters.compute(key) { _, current ->
            val next = (current ?: 1) - 1
            if (next <= 0) null else next
        }
    }

    private fun clientKey(): String =
        requestContext.clientIp.trim().lowercase().ifBlank { "unknown" }

    private companion object {
        const val CONCURRENCY_RETRY_AFTER_SECONDS = 1L
    }
}
