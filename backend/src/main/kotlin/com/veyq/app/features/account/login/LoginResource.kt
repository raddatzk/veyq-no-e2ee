package com.veyq.app.features.account.login

import com.veyq.app.generated.api.LoginApi
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.LoginChallenge
import com.veyq.app.generated.model.LoginChallengeRequest
import com.veyq.app.generated.model.LoginVerifyRequest
import com.veyq.app.generated.model.SessionFamily
import com.veyq.app.generated.model.SessionResponse
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for challenge-response login. Implements the generated [LoginApi]; thin — maps to/from the
 * generated models and delegates to [LoginHandler] (ADR 0001). Field validation runs at the boundary.
 */
class LoginResource(private val handler: LoginHandler) : LoginApi {

    override fun requestLoginChallenge(loginChallengeRequest: LoginChallengeRequest): Response {
        val challenge = handler.challenge(loginChallengeRequest.email)
        return Response.ok(
            LoginChallenge(nonce = challenge.nonce, instanceSalt = challenge.instanceSalt),
        ).build()
    }

    override fun verifyLogin(loginVerifyRequest: LoginVerifyRequest): Response {
        val session = handler.verify(loginVerifyRequest.email, loginVerifyRequest.signature)
            ?: return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ErrorResponse(code = "authentication_failed"))
                .build()
        return Response.ok(
            SessionResponse(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                family = SessionFamily(id = UUID.fromString(session.familyId)),
            ),
        ).build()
    }
}
