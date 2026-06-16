package com.veyq.app.platform.error

import com.veyq.app.generated.model.ErrorResponse
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

/** A handled API error mapped to an HTTP status + the shared ErrorResponse body. */
class ApiException(val status: Int, val code: String, override val message: String) :
    RuntimeException(message)

@Provider
class ApiExceptionMapper : ExceptionMapper<ApiException> {
    override fun toResponse(ex: ApiException): Response =
        Response.status(ex.status).entity(ErrorResponse(code = ex.code, message = ex.message)).build()
}

/**
 * Malformed client input — a bad base64 frame, an out-of-range enum value — surfaces from the JDK as a raw
 * IllegalArgumentException. Deliberate validation in handlers always throws [ApiException], so the only IAEs
 * that reach here come from decoding untrusted request data: map them to 400 instead of a generic 500. Still
 * logged at WARN with the cause so a genuine server-side IAE (should be none) stays visible.
 */
@Provider
class MalformedInputMapper : ExceptionMapper<IllegalArgumentException> {
    private val log = Logger.getLogger(MalformedInputMapper::class.java)

    override fun toResponse(ex: IllegalArgumentException): Response {
        log.warnf(ex, "Rejecting malformed request input: %s", ex.message)
        return Response.status(400)
            .entity(ErrorResponse(code = "INVALID_BLOB", message = "Malformed request data"))
            .build()
    }
}
