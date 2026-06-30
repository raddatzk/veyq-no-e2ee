package com.veyq.app.features.account.login

import com.veyq.app.generated.api.TokenApi
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.RefreshRequest
import com.veyq.app.generated.model.TokenPair
import com.veyq.app.shared.ratelimit.RateLimited
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/** HTTP adapter for refresh-token rotation. Implements the generated [TokenApi]; delegates to
 *  [SessionService] (rotation + reuse detection, ADR 0004). Public (`@PermitAll`, cr-0012): the refresh token
 *  *is* the credential, carried outside the bearer scheme — in the **httpOnly cookie** (web "remember me",
 *  cr-0052) or the request **body** (native). The cookie wins when present; the new token then rides a fresh
 *  cookie and is omitted from the body, so the refresh token never reaches JS on the web path. */
@PermitAll
@RateLimited
class TokenResource(private val sessions: SessionService, private val cookie: SessionCookie) : TokenApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun refreshSession(refreshRequest: RefreshRequest): Response {
        val fromCookie = cookie.read(headers)
        val token = fromCookie?.token ?: refreshRequest.refreshToken
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse(code = "invalid_request"))
                .build()
        val rotated = sessions.refresh(token)
            ?: return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse(code = "refresh_rejected"))
                .build()
        return if (fromCookie != null) {
            // Re-issue the SAME lifetime the request carried (persistent vs session) — so rotation never
            // silently upgrades/downgrades it (ADR 0004 addendum) — and clear the other lifetime, via the one
            // `issue` API. The token stays out of the body on the web path.
            Response.ok(TokenPair(accessToken = rotated.accessToken, refreshToken = null))
                .cookie(*cookie.issue(rotated.refreshToken, fromCookie.persistent)).build()
        } else {
            Response.ok(TokenPair(accessToken = rotated.accessToken, refreshToken = rotated.refreshToken)).build()
        }
    }
}
