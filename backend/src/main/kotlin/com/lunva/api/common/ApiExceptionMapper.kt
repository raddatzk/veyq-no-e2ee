package com.lunva.api.common

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

@Provider
class ApiExceptionMapper : ExceptionMapper<Throwable> {
    private val log: Logger = Logger.getLogger(ApiExceptionMapper::class.java)

    override fun toResponse(exception: Throwable): Response {
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

        log.error("Unhandled exception mapped to 500", exception)
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorResponse("Internal server error"))
            .build()
    }
}
