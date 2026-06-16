package com.veyq.app.shared.security

import com.veyq.app.generated.model.ErrorResponse
import io.vertx.ext.web.RoutingContext
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.InetAddress
import java.util.Optional

/**
 * Per-IP rate limiting for the abuse-prone unauthenticated endpoints. A request over its bucket's limit is
 * rejected with 429 before it reaches the handler. Disabled in dev/test (see application.properties) so the
 * slice + e2e suites, which hammer these paths from one host, stay green.
 */
@Provider
class RateLimitFilter(
    private val limiter: RateLimiter,
    @param:ConfigProperty(name = "veyq.ratelimit.enabled", defaultValue = "true") private val enabled: Boolean,
    // Optional<String> because SmallRye converts an empty config value to "absent" (the default deployment).
    @param:ConfigProperty(name = "veyq.ratelimit.trusted-proxies") trustedProxies: Optional<String>,
) : ContainerRequestFilter {

    @Inject
    lateinit var routingContext: RoutingContext

    /** Proxy IPs/CIDRs whose appended X-Forwarded-For we may trust (empty = trust none, key on the socket). */
    private val trusted: List<CidrMatcher> =
        trustedProxies.orElse("").split(",").map(String::trim).filter(String::isNotEmpty).mapNotNull(CidrMatcher::parseOrNull)

    /** (path-substring, limit, windowMs) — only POSTs to these paths are throttled. Generous vs real use. */
    private data class Bucket(val name: String, val match: String, val limit: Int, val windowMs: Long)

    private val buckets = listOf(
        Bucket("login", "/api/login", 10, 60_000),
        Bucket("reset-request", "/api/account/password-reset/request", 5, 60_000),
        Bucket("reset", "/api/account/password-reset/", 15, 60_000), // material + confirm
        Bucket("invite", "/api/tenant-invites/", 30, 60_000),
        Bucket("share-upload", "/api/public/shares/", 60, 60_000),
    )

    override fun filter(ctx: ContainerRequestContext) {
        if (!enabled || ctx.method != "POST") return
        val path = "/" + ctx.uriInfo.path.trimStart('/')
        val bucket = buckets.firstOrNull { path.contains(it.match) } ?: return
        if (!limiter.tryAcquire("${bucket.name}|${clientIp(ctx)}", bucket.limit, bucket.windowMs)) {
            ctx.abortWith(
                Response.status(429)
                    .header("Retry-After", bucket.windowMs / 1000)
                    .entity(ErrorResponse(code = "RATE_LIMITED", message = "Too many requests — please slow down"))
                    .build(),
            )
        }
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
                CidrMatcher(addr, prefix)
            }.getOrNull()
        }
    }
}
