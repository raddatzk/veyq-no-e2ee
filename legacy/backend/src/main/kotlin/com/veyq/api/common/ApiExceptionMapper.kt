package com.veyq.api.common

import com.veyq.api.security.RateLimitExceededException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.hibernate.exception.ConstraintViolationException
import org.jboss.logging.Logger
import java.sql.SQLIntegrityConstraintViolationException

@Provider
class ApiExceptionMapper : ExceptionMapper<Throwable> {
    private val log: Logger = Logger.getLogger(ApiExceptionMapper::class.java)

    override fun toResponse(exception: Throwable): Response {
        if (exception is RateLimitExceededException) {
            return Response.status(429)
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", exception.retryAfterSeconds.coerceAtLeast(1).toString())
                .entity(ErrorResponse("RATE_LIMITED", "Too many requests"))
                .build()
        }
        if (exception is ApiException) {
            return Response.status(exception.response.status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse(exception.code, exception.message ?: "Request failed"))
                .build()
        }
        if (exception is WebApplicationException) {
            val message = publicMessage(exception)
            if (exception.response.status >= 500) {
                log.error("WebApplicationException mapped to ${exception.response.status}", exception)
            }
            return Response.status(exception.response.status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse(genericCode(exception.response.status), message))
                .build()
        }
        if (exception is ConstraintViolationException) {
            val cause = exception.cause
            if (cause is SQLIntegrityConstraintViolationException) {
                return Response.status(409)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ErrorResponse("CONFLICT", "Conflict with existing resource"))
                    .build()
            }
        }

        log.error("Unhandled exception mapped to 500", exception)
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorResponse("INTERNAL_SERVER_ERROR", "Internal server error"))
            .build()
    }

    private fun publicMessage(exception: WebApplicationException): String =
        when {
            exception is NotFoundException || exception.response.status == 404 -> "Resource not found"
            exception.response.status >= 500 -> "Internal server error"
            else -> exception.message ?: "Request failed"
        }

    private fun genericCode(status: Int): String =
        when (status) {
            400 -> "BAD_REQUEST"
            401 -> "UNAUTHORIZED"
            403 -> "FORBIDDEN"
            404 -> "NOT_FOUND"
            409 -> "CONFLICT"
            413 -> "UPLOAD_TOO_LARGE"
            416 -> "RANGE_NOT_SATISFIABLE"
            429 -> "RATE_LIMITED"
            503 -> "SERVICE_UNAVAILABLE"
            in 500..599 -> "INTERNAL_SERVER_ERROR"
            else -> "REQUEST_FAILED"
        }
}
