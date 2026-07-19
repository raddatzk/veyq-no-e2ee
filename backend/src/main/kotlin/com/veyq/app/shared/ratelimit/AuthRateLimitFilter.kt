package com.veyq.app.shared.ratelimit

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.httpError
import io.smallrye.config.SmallRyeConfig
import io.vertx.core.http.HttpServerRequest
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.ConfigProvider
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
class AuthRateLimitFilter : ContainerRequestFilter {

    @Context
    private lateinit var request: HttpServerRequest

    // A JAX-RS @Provider is instantiated too early in Quarkus boot to take the VeyqConfig @ConfigMapping as a
    // CDI dependency (constructor OR field injection fails config-mapping registration). So we read the mapping
    // via a programmatic lookup, lazily on first request — the mapping is registered by then — keeping VeyqConfig
    // the single typed source for these knobs rather than falling back to scattered @ConfigProperty.
    private val auth: VeyqConfig.RateLimit.Auth by lazy {
        (ConfigProvider.getConfig() as SmallRyeConfig).getConfigMapping(VeyqConfig::class.java).ratelimit().auth()
    }
    private val maxRequests: Long by lazy { auth.maxRequests() }
    private val windowSeconds: Long by lazy { auth.windowSeconds() }

    private class Window(val windowStart: Long) { val count = AtomicLong(0) }

    private val buckets = ConcurrentHashMap<String, Window>()

    override fun filter(ctx: ContainerRequestContext) {
        val ip = request.remoteAddress()?.hostAddress() ?: "unknown"
        val nowSec = System.currentTimeMillis() / 1000
        // The fixed-window arithmetic (window start, elapsed, over-limit, retry-after) is the pure RateLimitWindow,
        // where it is mutation-verified (ADR 0045 §2); this filter only drives the clock + concurrent-map plumbing.
        val windowStart = RateLimitWindow.windowStart(nowSec, windowSeconds)

        // Bound the map so a flood of distinct (possibly spoofed) source IPs can't grow it without limit:
        // when it gets large, drop every entry whose window has already elapsed.
        if (buckets.size > MAX_TRACKED_IPS) {
            buckets.entries.removeIf { RateLimitWindow.hasElapsed(nowSec, it.value.windowStart, windowSeconds) }
        }

        val window = buckets.compute(ip) { _, existing ->
            if (existing != null && existing.windowStart == windowStart) existing else Window(windowStart)
        }!!

        if (RateLimitWindow.isOverLimit(window.count.incrementAndGet(), maxRequests)) {
            val retryAfter = RateLimitWindow.retryAfter(nowSec, windowSeconds)
            // The body is the shared ErrorResponse envelope (via httpError); this filter only adds Retry-After.
            ctx.abortWith(
                Response.fromResponse(httpError(Failures.rateLimited))
                    .header("Retry-After", retryAfter)
                    .build(),
            )
        }
    }

    private companion object {
        const val MAX_TRACKED_IPS = 100_000
    }
}
