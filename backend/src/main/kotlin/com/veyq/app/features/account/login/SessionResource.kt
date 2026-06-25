package com.veyq.app.features.account.login

import com.veyq.app.generated.api.SessionApi
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/** HTTP adapter for session lifecycle. Implements the generated [SessionApi]; reads the bearer access
 *  token and revokes its family (logout). Idempotent. */
class SessionResource(private val sessions: SessionService) : SessionApi {

    @Context
    lateinit var headers: HttpHeaders

    override fun logout(): Response {
        val token = headers.getHeaderString("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
        if (!token.isNullOrEmpty()) sessions.revokeByAccessToken(token)
        return Response.noContent().build()
    }
}
