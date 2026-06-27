package com.veyq.app.shared.ratelimit

import com.veyq.app.generated.model.ErrorResponse
import io.vertx.core.http.HttpServerRequest
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Best-effort, in-memory **fixed-window** rate limiter for the public auth endpoints (login challenge/verify,
 * token refresh) — defense-in-depth against brute-force / challenge-spam. Ed25519 makes login forgery
 * infeasible, so the real value is bounding DoS, challenge-row churn, and the rate of any offline-grind
 * enabling traffic.
 *
 * **Deliberate limitations** (a gateway / distributed bucket is the production answer, see backend CLAUDE.md):
 *  - **per-instance**: counters are not shared across a horizontally-scaled deployment;
 *  - **resets on restart**: the window map is in-memory only;
 *  - **keyed on the remote address**: only correct behind a proxy when
 *    `quarkus.http.proxy.proxy-address-forwarding=true` AND the proxy is trusted (else `X-Forwarded-For` is
 *    spoofable). We intentionally do NOT trust forwarded headers by default.
 *
 * Tuning: `veyq.ratelimit.auth.max-requests` (default 60) per `veyq.ratelimit.auth.window-seconds` (default
 * 60) per client IP. Set the max very high to effectively disable (the `%test` profile does this so the slice
 * tests, all from 127.0.0.1, don't trip it).
 */
@Provider
@RateLimited
@Priority(Priorities.AUTHENTICATION)
class AuthRateLimitFilter(
    @param:ConfigProperty(name = "veyq.ratelimit.auth.max-requests", defaultValue = "60")
    private val maxRequests: Long,
    @param:ConfigProperty(name = "veyq.ratelimit.auth.window-seconds", defaultValue = "60")
    private val windowSeconds: Long,
) : ContainerRequestFilter {

    @Context
    private lateinit var request: HttpServerRequest

    private class Window(val windowStart: Long) { val count = AtomicLong(0) }

    private val buckets = ConcurrentHashMap<String, Window>()

    override fun filter(ctx: ContainerRequestContext) {
        val ip = request.remoteAddress()?.hostAddress() ?: "unknown"
        val nowSec = System.currentTimeMillis() / 1000
        val windowStart = nowSec - (nowSec % windowSeconds)

        // Bound the map so a flood of distinct (possibly spoofed) source IPs can't grow it without limit:
        // when it gets large, drop every entry whose window has already elapsed.
        if (buckets.size > MAX_TRACKED_IPS) {
            buckets.entries.removeIf { nowSec - it.value.windowStart >= windowSeconds }
        }

        val window = buckets.compute(ip) { _, existing ->
            if (existing != null && existing.windowStart == windowStart) existing else Window(windowStart)
        }!!

        if (window.count.incrementAndGet() > maxRequests) {
            val retryAfter = windowSeconds - (nowSec % windowSeconds)
            ctx.abortWith(
                Response.status(429)
                    .header("Retry-After", retryAfter)
                    .entity(ErrorResponse(code = "rate_limited"))
                    .build(),
            )
        }
    }

    private companion object {
        const val MAX_TRACKED_IPS = 100_000
    }
}
