package com.veyq.app.platform.error

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not

@QuarkusTest
class UnhandledExceptionMapperTest {
    @Test
    fun `does not expose unhandled exception details`() {
        given()
            .get("/api/test/unhandled-exception")
            .then()
            .statusCode(500)
            .body("code", equalTo("INTERNAL_SERVER_ERROR"))
            .body("message", equalTo("Request failed"))
            .body("details", equalTo(null))
            .body("stack", equalTo(null))
            .body(not(containsString("RollbackException")))
            .body(not(containsString("Could not commit transaction")))
    }
}

@Path("/api/test/unhandled-exception")
class UnhandledExceptionTestResource {
    @GET
    fun throwUnhandled(): String {
        throw RuntimeException("jakarta.transaction.RollbackException: Could not commit transaction")
    }
}
