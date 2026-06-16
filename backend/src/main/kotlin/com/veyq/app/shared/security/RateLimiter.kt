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

    /** Drop windows untouched for a while so the map can't grow unbounded across many client IPs. */
    @Scheduled(every = "10m")
    fun sweep() {
        val now = System.nanoTime()
        windows.entries.removeIf { (_, w) -> synchronized(w) { now - w.startNanos > 600_000_000_000L } } // 10 min
    }
}
