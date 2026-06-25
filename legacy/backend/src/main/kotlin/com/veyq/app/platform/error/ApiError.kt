package com.veyq.app.platform.error

import com.veyq.app.generated.model.ErrorResponse
import jakarta.ws.rs.core.MediaType
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
        Response.status(ex.status).type(MediaType.APPLICATION_JSON).entity(ErrorResponse(code = ex.code, message = ex.message)).build()
}

/**
 * Bean-validation failures — the `@Valid`-cascaded `@field:…` constraints declared via the OpenAPI
 * `x-field-extra-annotation` extension — surface as a ConstraintViolationException. Map them to the shared
 * ErrorResponse shape (a stable 400) instead of Quarkus' default violation report, so every 400 the client
 * sees carries the same `{code, message}` body.
 */
@Provider
class ConstraintViolationMapper : ExceptionMapper<jakarta.validation.ConstraintViolationException> {
    override fun toResponse(ex: jakarta.validation.ConstraintViolationException): Response =
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorResponse(code = "VALIDATION_ERROR", message = ex.constraintViolations.firstOrNull()?.message ?: "Invalid request"))
            .build()
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
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorResponse(code = "INVALID_BLOB", message = "Malformed request data"))
            .build()
    }
}

/** Last-resort boundary: never expose framework, database or stack details in API error bodies. */
@Provider
class UnhandledExceptionMapper : ExceptionMapper<Exception> {
    private val log = Logger.getLogger(UnhandledExceptionMapper::class.java)

    override fun toResponse(ex: Exception): Response {
        log.error("Unhandled API exception", ex)
        return Response.serverError()
            .type(MediaType.APPLICATION_JSON)
            .entity(ErrorResponse(code = "INTERNAL_SERVER_ERROR", message = "Request failed"))
            .build()
    }
}
