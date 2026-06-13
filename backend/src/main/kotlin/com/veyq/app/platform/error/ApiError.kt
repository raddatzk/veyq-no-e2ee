package com.veyq.app.platform.error

import com.veyq.app.generated.model.ErrorResponse
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/** A handled API error mapped to an HTTP status + the shared ErrorResponse body. */
class ApiException(val status: Int, val code: String, override val message: String) :
    RuntimeException(message)

@Provider
class ApiExceptionMapper : ExceptionMapper<ApiException> {
    override fun toResponse(ex: ApiException): Response =
        Response.status(ex.status).entity(ErrorResponse(code = ex.code, message = ex.message)).build()
}
