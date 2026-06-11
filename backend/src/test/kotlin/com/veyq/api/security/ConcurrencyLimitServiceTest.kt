package com.veyq.api.security

import com.veyq.api.auth.filterchain.RequestContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyLimitServiceTest {
    @Test
    fun `rejects long polls beyond the per-ip limit`() {
        val requestContext = RequestContext().apply { clientIp = "203.0.113.10" }
        val service = ConcurrencyLimitService(requestContext, securityConfig(perIp = 2, total = 64))

        withConcurrentHolders(service, holders = 2) {
            assertThatThrownBy {
                service.withPublicShareLongPoll { "should not run" }
            }.isInstanceOf(RateLimitExceededException::class.java)
        }
    }

    @Test
    fun `rejects long polls beyond the global limit even across ips`() {
        val requestContext = RequestContext()
        val service = ConcurrencyLimitService(requestContext, securityConfig(perIp = 10, total = 2))

        requestContext.clientIp = "203.0.113.10"
        withConcurrentHolders(service, holders = 2) {
            requestContext.clientIp = "203.0.113.11"
            assertThatThrownBy {
                service.withPublicShareLongPoll { "should not run" }
            }.isInstanceOf(RateLimitExceededException::class.java)
        }
    }

    @Test
    fun `releases slots once the long poll completes`() {
        val requestContext = RequestContext().apply { clientIp = "203.0.113.10" }
        val service = ConcurrencyLimitService(requestContext, securityConfig(perIp = 1, total = 1))

        repeat(5) {
            assertThat(service.withPublicShareLongPoll { "ok" }).isEqualTo("ok")
        }
    }

    /**
     * Holds [holders] concurrent long-poll slots open while [whileHeld] runs, then releases them.
     */
    private fun withConcurrentHolders(
        service: ConcurrencyLimitService,
        holders: Int,
        whileHeld: () -> Unit,
    ) {
        val started = CountDownLatch(holders)
        val release = CountDownLatch(1)
        val running = AtomicInteger(0)
        val threads = (1..holders).map {
            Thread {
                service.withPublicShareLongPoll {
                    running.incrementAndGet()
                    started.countDown()
                    release.await()
                }
            }.also { it.start() }
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(running.get()).isEqualTo(holders)
            whileHeld()
        } finally {
            release.countDown()
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
        }
    }

    private fun securityConfig(perIp: Int, total: Int): SecurityConfig =
        object : SecurityConfig {
            override val trustForwardedFor = false
            override val publicShareUpdates = object : SecurityConfig.PublicShareUpdates {
                override val maxConcurrentPerIp = perIp
                override val maxConcurrentTotal = total
            }
        }
}
