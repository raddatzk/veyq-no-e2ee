package com.veyq.api.common

import com.veyq.api.security.RateLimitExceededException
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
                .entity(ErrorResponse("Too many requests"))
                .build()
        }
        if (exception is WebApplicationException) {
            val message = exception.message ?: "Request failed"
            if (exception.response.status >= 500) {
                log.error("WebApplicationException mapped to ${exception.response.status}", exception)
            }
            return Response.status(exception.response.status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorResponse(message))
                .build()
        }
        if (exception is ConstraintViolationException) {
            val cause = exception.cause
            if (cause is SQLIntegrityConstraintViolationException) {
                val message = cause.message ?: "Constraint violated"
                return Response.status(409)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ErrorResponse(message))
                    .build()
            }
        }

        log.error("Unhandled exception mapped to 500", exception)
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorResponse("Internal server error"))
            .build()
    }
}
