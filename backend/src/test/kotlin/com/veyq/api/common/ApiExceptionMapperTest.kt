package com.veyq.api.common

import com.veyq.api.auth.BadRequestException
import com.veyq.api.security.RateLimitExceededException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import java.sql.SQLIntegrityConstraintViolationException

class ApiExceptionMapperTest {
    private val mapper = ApiExceptionMapper()

    @Test
    fun `maps api exceptions with their stable code`() {
        val response = mapper.toResponse(BadRequestException("Invalid crop", "MEMORY_COVER_CROP_INVALID"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.entity).isEqualTo(ErrorResponse("MEMORY_COVER_CROP_INVALID", "Invalid crop"))
    }

    @Test
    fun `maps rate limits with retry after and stable code`() {
        val response = mapper.toResponse(RateLimitExceededException(12))

        assertThat(response.status).isEqualTo(429)
        assertThat(response.getHeaderString("Retry-After")).isEqualTo("12")
        assertThat(response.entity).isEqualTo(ErrorResponse("RATE_LIMITED", "Too many requests"))
    }

    @Test
    fun `maps generic web exceptions to generic status codes`() {
        val response = mapper.toResponse(WebApplicationException("Nope", Response.Status.FORBIDDEN))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity).isEqualTo(ErrorResponse("FORBIDDEN", "Nope"))
    }

    @Test
    fun `does not expose persistence constraint details`() {
        val response = mapper.toResponse(
            ConstraintViolationException(
                "Duplicate key user_email_unique",
                SQLIntegrityConstraintViolationException("Duplicate key user_email_unique"),
                "users",
            )
        )

        assertThat(response.status).isEqualTo(409)
        assertThat(response.entity).isEqualTo(ErrorResponse("CONFLICT", "Conflict with existing resource"))
    }

    @Test
    fun `does not expose unhandled exception details`() {
        val response = mapper.toResponse(IllegalStateException("secret internals"))

        assertThat(response.status).isEqualTo(500)
        assertThat(response.entity).isEqualTo(ErrorResponse("INTERNAL_SERVER_ERROR", "Internal server error"))
    }
}
