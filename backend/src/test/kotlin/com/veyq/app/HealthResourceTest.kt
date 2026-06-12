package com.veyq.app

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.`is`
import kotlin.test.Test

@QuarkusTest
class HealthResourceTest {
    @Test
    fun health() {
        given()
            .`when`().get("/api/health")
            .then()
            .statusCode(200)
            .body("name", `is`("veyq"))
            .body("status", `is`("UP"))
    }
}
