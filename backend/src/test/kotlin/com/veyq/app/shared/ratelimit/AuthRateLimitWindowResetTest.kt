package com.veyq.app.shared.ratelimit

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Verifies [AuthRateLimitFilter]'s **fixed window actually resets**: past the limit an IP is throttled, but
 * once the window elapses its counter clears and requests are allowed again. Split out from [AuthRateLimitTest]
 * because it needs a **1-second** window (so the reset is observable in real time without a long wait) — a
 * different build-time/runtime config, hence its own profile. Proxy-address-forwarding is on so this test keys
 * its own client IP, isolated from everything else.
 */
@QuarkusTest
@TestProfile(AuthRateLimitWindowResetTest.ShortWindowProfile::class)
class AuthRateLimitWindowResetTest {

    class ShortWindowProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.auth.max-requests" to "3",
            "veyq.ratelimit.auth.window-seconds" to "1",
            "quarkus.http.proxy.proxy-address-forwarding" to "true",
            "quarkus.http.proxy.allow-x-forwarded" to "true",
        )
    }

    @Test
    fun theCounterClearsAfterTheWindowElapses() {
        val ip = "203.0.113.7"
        // Trip the limit inside the current 1-second window (fire a few extra to absorb any window-boundary
        // straddle — we only need to observe that the throttle engages at all).
        var tripped = false
        repeat(8) { if (challenge(ip).statusCode() == 429) tripped = true }
        assertTrue(tripped, "expected the limit to trip within the window")

        // Once the window elapses the per-IP counter resets and requests are allowed again.
        assertTrue(pollUntilAllowed(ip, Duration.ofSeconds(3)), "expected the window to reset and allow requests again")
    }

    private fun pollUntilAllowed(ip: String, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (challenge(ip).statusCode() == 200) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun challenge(ip: String): Response =
        given().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
            .body("""{"email":"ratelimit@example.com"}""")
            .`when`().post("/api/login/challenge")
}
