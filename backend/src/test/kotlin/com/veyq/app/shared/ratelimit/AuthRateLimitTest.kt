package com.veyq.app.shared.ratelimit

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Verifies [AuthRateLimitFilter] actually throttles the public auth surface. Runs under a profile that drops
 * the limit to 3/window so a few requests trip it (the normal `%test` profile sets it sky-high so the other
 * slice tests, all from 127.0.0.1, never collide). The login-challenge endpoint is non-enumerable (200 for
 * any email), so no user seeding is needed — we only care that the (N+1)th call is a 429.
 */
@QuarkusTest
@TestProfile(AuthRateLimitTest.LowLimitProfile::class)
class AuthRateLimitTest {

    class LowLimitProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.ratelimit.auth.max-requests" to "3",
            "veyq.ratelimit.auth.window-seconds" to "60",
        )
    }

    @Test
    fun challengeIsThrottledOverTheLimit() {
        // 3 requests are allowed inside the window...
        repeat(3) { challenge().then().statusCode(200) }
        // ...the 4th from the same IP is rejected with 429 + the rate_limited code.
        challenge().then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    private fun challenge() =
        given().contentType(ContentType.JSON).body("""{"email":"ratelimit@example.com"}""")
            .`when`().post("/api/login/challenge")
}
