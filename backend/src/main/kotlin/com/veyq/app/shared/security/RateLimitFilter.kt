package com.veyq.app.shared.security

import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.shared.config.RateLimitConfig
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import java.net.InetAddress

/**
 * Per-IP rate limiting for the abuse-prone unauthenticated endpoints. A request over its bucket's limit is
 * rejected with 429 before it reaches the handler. Disabled in dev/test (see application.properties) so the
 * slice + e2e suites, which hammer these paths from one host, stay green.
 */
@Provider
@ApplicationScoped // lazy: resolve the @ConfigMapping at first request, not during early-boot provider wiring
class RateLimitFilter(
    private val limiter: RateLimiter,
    config: RateLimitConfig,
) : ContainerRequestFilter {

    private val enabled: Boolean = config.enabled()
    // Per-IP per-minute egress budget for unauthenticated public-share GETs (default 500 MiB).
    private val shareEgressBytesPerMin: Long = config.shareEgressBytesPerMin()
    private val inviteReadPerMin: Int = config.inviteReadPerMin()
    private val shareChunkPerMin: Int = config.shareChunkPerMin()

    @Inject
    lateinit var routingContext: RoutingContext

    companion object {
        /** Request property carrying the resolved client IP, set here and read by ShareEgressAccountingFilter. */
        const val CLIENT_IP_PROP = "veyq.clientIp"
    }

    /** Proxy IPs/CIDRs whose appended X-Forwarded-For we may trust (empty = trust none, key on the socket). */
    private val trusted: List<CidrMatcher> =
        config.trustedProxies().orElse("").split(",").map(String::trim).filter(String::isNotEmpty).mapNotNull(CidrMatcher::parseOrNull)

    /** (path-substring, limit, windowMs) — only POSTs to these paths are throttled. Generous vs real use. */
    private data class Bucket(val name: String, val match: String, val limit: Int, val windowMs: Long)

    private val buckets = listOf(
        Bucket("login", "/api/login", config.loginPerMin(), 60_000),
        Bucket("reset-request", "/api/me/password-reset/request", config.resetRequestPerMin(), 60_000),
        Bucket("reset", "/api/me/password-reset/", config.resetPerMin(), 60_000), // material + confirm
        Bucket("invite", "/api/tenant-invites/", config.invitePerMin(), 60_000),
        Bucket("share-upload", "/api/public/shares/", config.shareUploadPerMin(), 60_000),
    )

    override fun filter(ctx: ContainerRequestContext) {
        if (!enabled) return
        val ip = clientIp(ctx)
        ctx.setProperty(CLIENT_IP_PROP, ip) // ShareEgressAccountingFilter reads this to attribute bytes served
        val path = "/" + ctx.uriInfo.path.trimStart('/')

        // Unauthenticated public-share GET egress (gallery/journal/chunk downloads): once an IP has pulled more
        // than its per-minute byte budget, refuse further GETs until the window rolls. A byte quota (not a
        // request count) avoids 429-ing a legitimate large-gallery viewer, which makes many small chunk GETs.
        if (ctx.method == "GET") {
            // Unauthenticated invite-metadata GET: a per-IP request-count cap (UUIDs aren't enumerable, but it's
            // an otherwise-unthrottled DB read + tenant-existence oracle). Generous — a guest opens an invite once.
            if (path.contains("/api/tenant-invites/") && !limiter.tryAcquire("invite-read|$ip", inviteReadPerMin, 60_000)) {
                reject(ctx, 60_000)
                return
            }
            if (path.contains("/api/public/shares/") && limiter.isOverByteBudget("share-egress|$ip", shareEgressBytesPerMin, 60_000)) {
                reject(ctx, 60_000)
            }
            return
        }
        // Guest share uploads PUT each chunk (only the session start/complete are POST), so the POST buckets
        // below never see them. Throttle chunk PUTs to the public-share path per IP — generous (600/min ≈
        // 10/s) so a real multi-chunk upload sails through, while total stored volume stays bounded by the
        // per-share byte cap in BlobUploadHandler. Without this a guest could hammer the chunk endpoint
        // (e.g. idempotent re-PUTs of one index) entirely unthrottled.
        if (ctx.method == "PUT") {
            if (path.contains("/api/public/shares/") && !limiter.tryAcquire("share-chunk|$ip", shareChunkPerMin, 60_000)) {
                reject(ctx, 60_000)
            }
            return
        }
        if (ctx.method != "POST") return
        val bucket = buckets.firstOrNull { path.contains(it.match) } ?: return
        if (!limiter.tryAcquire("${bucket.name}|$ip", bucket.limit, bucket.windowMs)) {
            reject(ctx, bucket.windowMs)
        }
    }

    private fun reject(ctx: ContainerRequestContext, windowMs: Long) {
        ctx.abortWith(
            Response.status(429)
                .header("Retry-After", windowMs / 1000)
                .entity(ErrorResponse(code = "RATE_LIMITED", message = "Too many requests — please slow down"))
                .build(),
        )
    }

    private fun clientIp(ctx: ContainerRequestContext): String {
        val peer = runCatching { routingContext.request().remoteAddress()?.hostAddress() }.getOrNull()
        return resolveClientIp(peer, ctx.getHeaderString("X-Forwarded-For"))
    }

    /**
     * Real client IP for the rate-limit key. X-Forwarded-For is client-controllable, so honoring it blindly
     * lets an attacker mint a fresh bucket per request by rotating the header — defeating the throttle. We
     * therefore trust XFF ONLY when the actual socket peer is a configured trusted proxy; then we walk the
     * header right-to-left (proxies append on the right) and take the first hop that isn't itself a trusted
     * proxy. With no trusted proxy configured (dev, or a direct-exposed deploy) XFF is ignored and we key on
     * the socket peer. Pure (no JAX-RS/Vert.x state) so it is unit-testable.
     */
    fun resolveClientIp(peer: String?, xff: String?): String {
        val socket = peer ?: "unknown"
        if (trusted.isEmpty() || !isTrusted(socket)) return socket
        val hops = xff?.split(",")?.map(String::trim)?.filter(String::isNotEmpty) ?: return socket
        return hops.lastOrNull { !isTrusted(it) } ?: socket
    }

    private fun isTrusted(ip: String): Boolean {
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        return trusted.any { it.matches(addr) }
    }

    /** Matches an IP against a single IP or CIDR (e.g. "10.0.0.0/8", "::1", "2001:db8::/32"). */
    private class CidrMatcher(private val network: ByteArray, private val prefixBits: Int) {
        fun matches(addr: InetAddress): Boolean {
            val bytes = addr.address
            if (bytes.size != network.size) return false // never cross IPv4/IPv6 families
            var remaining = prefixBits
            var i = 0
            while (remaining >= 8) {
                if (bytes[i] != network[i]) return false
                i++; remaining -= 8
            }
            if (remaining == 0) return true
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            return (bytes[i].toInt() and mask) == (network[i].toInt() and mask)
        }

        companion object {
            fun parseOrNull(spec: String): CidrMatcher? = runCatching {
                val hasPrefix = "/" in spec
                val ip = if (hasPrefix) spec.substringBefore("/") else spec
                val addr = InetAddress.getByName(ip).address
                val prefix = if (hasPrefix) spec.substringAfter("/").toInt() else addr.size * 8
                require(prefix in 0..(addr.size * 8))
                // Canonicalize: zero the host bits so the stored network is the true prefix regardless of what
                // the operator wrote (e.g. "10.0.0.5/24" → 10.0.0.0/24). matches() then compares like-for-like.
                CidrMatcher(maskToPrefix(addr, prefix), prefix)
            }.getOrNull()

            /** Return a copy of [addr] with every bit beyond [prefixBits] cleared (the canonical network). */
            private fun maskToPrefix(addr: ByteArray, prefixBits: Int): ByteArray {
                val out = addr.copyOf()
                for (i in out.indices) {
                    val keep = (prefixBits - i * 8).coerceIn(0, 8) // bits to retain in this byte
                    val mask = if (keep == 0) 0x00 else (0xFF shl (8 - keep)) and 0xFF
                    out[i] = (out[i].toInt() and mask).toByte()
                }
                return out
            }
        }
    }
}
