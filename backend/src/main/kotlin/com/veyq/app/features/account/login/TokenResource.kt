package com.veyq.app.features.account.login

import com.veyq.app.generated.api.TokenApi
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.RefreshRequest
import com.veyq.app.generated.model.TokenPair
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response

/** HTTP adapter for refresh-token rotation. Implements the generated [TokenApi]; delegates to
 *  [SessionService] (rotation + reuse detection, ADR 0004). Public (`@PermitAll`, cr-0012): the refresh token
 *  in the body *is* the credential, carried outside the bearer access-token scheme. */
@PermitAll
class TokenResource(private val sessions: SessionService) : TokenApi {

    override fun refreshSession(refreshRequest: RefreshRequest): Response {
        val rotated = sessions.refresh(refreshRequest.refreshToken)
            ?: return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse(code = "refresh_rejected"))
                .build()
        return Response.ok(
            TokenPair(accessToken = rotated.accessToken, refreshToken = rotated.refreshToken),
        ).build()
    }
}
