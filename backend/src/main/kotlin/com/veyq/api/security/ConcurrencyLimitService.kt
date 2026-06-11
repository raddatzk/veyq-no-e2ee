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
    private val perKeyCounters = ConcurrentHashMap<String, AtomicInteger>()
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
        val counter = perKeyCounters.computeIfAbsent(key) { AtomicInteger(0) }
        if (counter.incrementAndGet() > perKeyLimit) {
            counter.decrementAndGet()
            throw RateLimitExceededException(CONCURRENCY_RETRY_AFTER_SECONDS)
        }
        if (totalCounter.incrementAndGet() > totalLimit) {
            totalCounter.decrementAndGet()
            counter.decrementAndGet()
            throw RateLimitExceededException(CONCURRENCY_RETRY_AFTER_SECONDS)
        }
    }

    private fun release(key: String) {
        totalCounter.decrementAndGet()
        val counter = perKeyCounters[key] ?: return
        if (counter.decrementAndGet() <= 0) {
            // Only drop the bucket if it is still empty, so we do not retain a
            // per-IP entry for every caller that has ever connected.
            perKeyCounters.remove(key, counter)
        }
    }

    private fun clientKey(): String =
        requestContext.clientIp.trim().lowercase().ifBlank { "unknown" }

    private companion object {
        const val CONCURRENCY_RETRY_AFTER_SECONDS = 1L
    }
}
