package com.veyq.app.shared.security

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny in-memory fixed-window rate limiter (per (key) counter that resets each window). Used to throttle
 * abuse-prone unauthenticated endpoints (login, password-reset, invite redemption, public-share upload) so
 * a single host can't spray credentials/invite secrets or flood guest uploads. Best-effort and per-instance
 * (no shared store) — enough to blunt automated abuse on a self-hosted node; a clustered deployment would
 * front this with a shared limiter at the proxy.
 */
@ApplicationScoped
class RateLimiter {
    private class Window {
        var startNanos: Long = System.nanoTime()
        var count: Int = 0
    }

    private val windows = ConcurrentHashMap<String, Window>()

    /** Record one hit for [key]; return false once more than [limit] hits land within [windowMs]. */
    fun tryAcquire(key: String, limit: Int, windowMs: Long): Boolean {
        val w = windows.computeIfAbsent(key) { Window() }
        synchronized(w) {
            val now = System.nanoTime()
            if (now - w.startNanos > windowMs * 1_000_000L) {
                w.startNanos = now
                w.count = 0
            }
            w.count++
            return w.count <= limit
        }
    }

    // Byte-quota windows: a separate accumulator keyed independently from the request-count windows. Used to
    // bound unauthenticated public-share download egress — the response is accounted AFTER it is served, so a
    // scraper is cut off on its NEXT request once it has pulled more than the budget within the window. A byte
    // quota (not a request count) is right here: a legitimate large-gallery viewer makes many small chunk GETs.
    private class ByteWindow {
        var startNanos: Long = System.nanoTime()
        var bytes: Long = 0
    }

    private val byteWindows = ConcurrentHashMap<String, ByteWindow>()

    private fun rollByteWindow(w: ByteWindow, windowMs: Long) {
        val now = System.nanoTime()
        if (now - w.startNanos > windowMs * 1_000_000L) { w.startNanos = now; w.bytes = 0 }
    }

    /** True if [key] has already accumulated more than [budgetBytes] within [windowMs] (window auto-resets). */
    fun isOverByteBudget(key: String, budgetBytes: Long, windowMs: Long): Boolean {
        val w = byteWindows.computeIfAbsent(key) { ByteWindow() }
        synchronized(w) {
            rollByteWindow(w, windowMs)
            return w.bytes > budgetBytes
        }
    }

    /** Account [bytes] served for [key] in its current window (no-op for non-positive byte counts). */
    fun recordBytes(key: String, bytes: Long, windowMs: Long) {
        if (bytes <= 0) return
        val w = byteWindows.computeIfAbsent(key) { ByteWindow() }
        synchronized(w) {
            rollByteWindow(w, windowMs)
            w.bytes += bytes
        }
    }

    /** Drop windows untouched for a while so the maps can't grow unbounded across many client IPs. */
    @Scheduled(every = "10m")
    fun sweep() {
        val now = System.nanoTime()
        windows.entries.removeIf { (_, w) -> synchronized(w) { now - w.startNanos > 600_000_000_000L } } // 10 min
        byteWindows.entries.removeIf { (_, w) -> synchronized(w) { now - w.startNanos > 600_000_000_000L } }
    }
}
