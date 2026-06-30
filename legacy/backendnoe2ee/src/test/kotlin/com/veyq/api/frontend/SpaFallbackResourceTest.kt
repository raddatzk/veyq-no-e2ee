package com.veyq.api.frontend

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test

@QuarkusTest
class SpaFallbackResourceTest {

    @Test
    fun `spa fallback injects Safari Smart App Banner metadata for app-openable routes`() {
        given()
            .`when`().get("/memories/summer-2026?view=journal")
            .then()
            .statusCode(200)
            .body(containsString("<meta name=\"apple-itunes-app\""))
            .body(containsString("app-id=1234567890"))
            .body(containsString("app-argument=http://localhost:"))
            .body(containsString("/memories/summer-2026?view=journal"))
    }

    @Test
    fun `spa fallback does not shadow backend api paths`() {
        given()
            .`when`().get("/api/frontend/bootstrap")
            .then()
            .statusCode(401)
    }
}
