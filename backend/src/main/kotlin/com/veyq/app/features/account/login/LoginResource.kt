package com.veyq.app.features.account.login

import com.veyq.app.generated.api.LoginApi
import com.veyq.app.generated.model.ErrorResponse
import com.veyq.app.generated.model.LoginChallenge
import com.veyq.app.generated.model.LoginChallengeRequest
import com.veyq.app.generated.model.LoginVerifyRequest
import com.veyq.app.generated.model.SessionFamily
import com.veyq.app.generated.model.SessionResponse
import com.veyq.app.shared.ratelimit.RateLimited
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * HTTP adapter for challenge-response login. Implements the generated [LoginApi]; thin — maps to/from the
 * generated models and delegates to [LoginHandler] (ADR 0001). Field validation runs at the boundary. Public
 * (`@PermitAll`, cr-0012): login *establishes* the session, so it precedes authentication.
 */
@PermitAll
@RateLimited
class LoginResource(private val handler: LoginHandler, private val cookie: SessionCookie) : LoginApi {

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
        val family = SessionFamily(id = UUID.fromString(session.familyId))
        // Pick the refresh transport (ADR 0004 addendum / ADR 0005 §22). Web sends `sessionScope`; native omits
        // it. Compare by wire value so the exact generated enum constant doesn't matter. `remember` is the legacy
        // web flag, honoured (→ persistent) only when `sessionScope` is absent. Operator-blind in every case.
        val noBodyToken = SessionResponse(accessToken = session.accessToken, family = family, refreshToken = null)
        // Set ONE refresh cookie and CLEAR the other lifetime — exactly one refresh cookie per device, so a
        // leftover persistent cookie can't shadow a fresh session one on refresh (read() prefers persistent).
        return when (loginVerifyRequest.sessionScope?.value) {
            "PERSISTENT" ->
                Response.ok(noBodyToken).cookie(cookie.persistent(session.refreshToken), cookie.clearedSession()).build()
            "SESSION" ->
                Response.ok(noBodyToken).cookie(cookie.session(session.refreshToken), cookie.clearedPersistent()).build()
            else ->
                if (loginVerifyRequest.remember == true) {
                    Response.ok(noBodyToken).cookie(cookie.persistent(session.refreshToken), cookie.clearedSession()).build()
                } else {
                    Response.ok(
                        SessionResponse(accessToken = session.accessToken, family = family, refreshToken = session.refreshToken),
                    ).build()
                }
        }
    }
}
