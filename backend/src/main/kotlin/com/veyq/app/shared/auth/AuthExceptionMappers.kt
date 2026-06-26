package com.veyq.app.shared.auth

import com.veyq.app.generated.model.ErrorResponse
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.ForbiddenException
import io.quarkus.security.UnauthorizedException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * The single home of the auth-failure response body (cr-0012). Quarkus REST honours these mappers for its
 * security exceptions, so every annotation-gated endpoint emits the same contract `ErrorResponse` — no
 * per-Resource `unauthorized()` / `forbidden()` anymore. The codes match the previous hand-written ones, so the
 * wire contract is unchanged.
 *
 * 401 stays deliberately generic (`authentication_failed`): a missing, malformed, expired, or revoked token are
 * indistinguishable to the caller (auth ≠ unlock — a stolen token yields only ciphertext).
 */
@Provider
class AuthenticationFailedExceptionMapper : ExceptionMapper<AuthenticationFailedException> {
    override fun toResponse(exception: AuthenticationFailedException): Response = unauthorized()
}

@Provider
class UnauthorizedExceptionMapper : ExceptionMapper<UnauthorizedException> {
    override fun toResponse(exception: UnauthorizedException): Response = unauthorized()
}

@Provider
class ForbiddenExceptionMapper : ExceptionMapper<ForbiddenException> {
    override fun toResponse(exception: ForbiddenException): Response =
        Response.status(Response.Status.FORBIDDEN)
            .entity(ErrorResponse(code = "not_server_owner"))
            .build()
}

private fun unauthorized(): Response =
    Response.status(Response.Status.UNAUTHORIZED)
        .entity(ErrorResponse(code = "authentication_failed"))
        .build()
