package com.veyq.app.shared.security

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Accounts the bytes served by unauthenticated public-share GETs (gallery/journal/chunk downloads) into the
 * per-IP egress window, so [RateLimitFilter] can cut off an IP that has pulled more than its per-minute byte
 * budget. Bounds bandwidth amplification via share links without throttling a legitimate viewer (whose many
 * chunk GETs are small). Runs only when the request filter resolved a client IP (i.e. rate limiting enabled).
 */
@Provider
class ShareEgressAccountingFilter(private val limiter: RateLimiter) : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, res: ContainerResponseContext) {
        if (req.method != "GET") return
        val path = "/" + req.uriInfo.path.trimStart('/')
        if (!path.contains("/api/public/shares/")) return
        val ip = req.getProperty(RateLimitFilter.CLIENT_IP_PROP) as? String ?: return
        // res.length is the Content-Length the server set (-1 if unknown). The chunk path returns a fixed byte[]
        // so its length is known; for the small metadata responses an under-count is harmless.
        val len = if (res.length >= 0) res.length.toLong() else (res.getHeaderString("Content-Length")?.toLongOrNull() ?: 0L)
        limiter.recordBytes("share-egress|$ip", len, 60_000)
    }
}
