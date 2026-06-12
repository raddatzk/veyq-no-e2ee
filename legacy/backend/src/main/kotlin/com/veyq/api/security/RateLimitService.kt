package com.veyq.api.security

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.ApiException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class RateLimitExceededException(
    val retryAfterSeconds: Long,
) : ApiException(
    "RATE_LIMITED",
    "Too many requests",
    Response.Status.TOO_MANY_REQUESTS,
)

@ApplicationScoped
class RateLimitService @Inject constructor(
    private val requestContext: RequestContext,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val clock: Clock = Clock.systemUTC()

    fun checkLoginAttempt(email: String) {
        consume("auth:login:ip:${clientKey()}", 30, Duration.ofMinutes(15))
        check("auth:login:email:${normalize(email)}:ip:${clientKey()}", 5, Duration.ofMinutes(15))
    }

    fun recordLoginFailure(email: String) {
        consume("auth:login:email:${normalize(email)}:ip:${clientKey()}", 5, Duration.ofMinutes(15))
    }

    fun recordLoginSuccess(email: String) {
        clear("auth:login:email:${normalize(email)}:ip:${clientKey()}")
    }

    fun checkShareLoginAttempt(shareId: String) {
        consume("share:login:ip:${clientKey()}", 60, Duration.ofMinutes(15))
        check("share:login:${normalize(shareId)}:ip:${clientKey()}", 5, Duration.ofMinutes(15))
    }

    fun recordShareLoginFailure(shareId: String) {
        consume("share:login:${normalize(shareId)}:ip:${clientKey()}", 5, Duration.ofMinutes(15))
    }

    fun recordShareLoginSuccess(shareId: String) {
        clear("share:login:${normalize(shareId)}:ip:${clientKey()}")
    }

    fun checkForgotPassword(email: String) {
        consume("auth:forgot:email:${normalize(email)}", 3, Duration.ofHours(1))
        consume("auth:forgot:ip:${clientKey()}", 10, Duration.ofHours(1))
    }

    fun checkTokenFailureAllowed(purpose: String, target: String) {
        check("auth:token:$purpose:${normalize(target)}:ip:${clientKey()}", 5, Duration.ofMinutes(15))
    }

    fun recordTokenFailure(purpose: String, target: String) {
        consume("auth:token:$purpose:${normalize(target)}:ip:${clientKey()}", 5, Duration.ofMinutes(15))
    }

    fun recordTokenSuccess(purpose: String, target: String) {
        clear("auth:token:$purpose:${normalize(target)}:ip:${clientKey()}")
    }

    fun checkPublicAttempt(purpose: String) {
        consume("public:$purpose:ip:${clientKey()}", 30, Duration.ofMinutes(15))
    }

    fun clearAll() {
        buckets.clear()
    }

    private fun check(key: String, limit: Int, window: Duration) {
        val now = Instant.now(clock)
        val bucket = buckets.compute(key) { _, current ->
            current?.takeIf { it.expiresAt.isAfter(now) } ?: Bucket(0, now.plus(window))
        }!!
        if (bucket.count >= limit) throw RateLimitExceededException(Duration.between(now, bucket.expiresAt).seconds)
    }

    private fun consume(key: String, limit: Int, window: Duration) {
        val now = Instant.now(clock)
        val bucket = buckets.compute(key) { _, current ->
            val active = current?.takeIf { it.expiresAt.isAfter(now) } ?: Bucket(0, now.plus(window))
            active.copy(count = active.count + 1)
        }!!
        if (bucket.count > limit) throw RateLimitExceededException(Duration.between(now, bucket.expiresAt).seconds)
    }

    private fun clear(key: String) {
        buckets.remove(key)
    }

    private fun clientKey(): String =
        requestContext.clientIp.trim().lowercase().ifBlank { "unknown" }

    private fun normalize(value: String): String =
        value.trim().lowercase().ifBlank { "unknown" }

    private data class Bucket(
        val count: Int,
        val expiresAt: Instant,
    )
}
