package com.veyq.app.shared.ratelimit

import jakarta.ws.rs.NameBinding

/**
 * Marks a JAX-RS resource (or method) as subject to the public-auth rate limiter ([AuthRateLimitFilter]).
 * Name-bound so the filter applies ONLY where annotated — the authed endpoints are already protected by the
 * bearer scheme; this is for the `@PermitAll` login/refresh surface.
 */
@NameBinding
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimited
