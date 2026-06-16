package com.veyq.app.shared.security

import com.veyq.app.generated.model.ErrorResponse
import io.vertx.ext.web.RoutingContext
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Per-IP rate limiting for the abuse-prone unauthenticated endpoints. A request over its bucket's limit is
 * rejected with 429 before it reaches the handler. Disabled in dev/test (see application.properties) so the
 * slice + e2e suites, which hammer these paths from one host, stay green.
 */
@Provider
class RateLimitFilter(
    private val limiter: RateLimiter,
    @param:ConfigProperty(name = "veyq.ratelimit.enabled", defaultValue = "true") private val enabled: Boolean,
) : ContainerRequestFilter {

    @Inject
    lateinit var routingContext: RoutingContext

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

    /** Real client IP: the first X-Forwarded-For hop if present (we sit behind a proxy), else the socket. */
    private fun clientIp(ctx: ContainerRequestContext): String {
        ctx.getHeaderString("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { routingContext.request().remoteAddress()?.hostAddress() }.getOrNull() ?: "unknown"
    }
}
