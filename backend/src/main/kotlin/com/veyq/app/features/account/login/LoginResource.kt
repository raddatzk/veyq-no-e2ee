package com.veyq.app.features.account.login

import com.veyq.app.generated.api.LoginApi
import com.veyq.app.generated.model.ChangePasswordRequest
import com.veyq.app.generated.model.DeleteAccountRequest
import com.veyq.app.generated.model.LoginRequest
import com.veyq.app.generated.model.RefreshSessionResponse
import com.veyq.app.generated.model.UpdateAvatarRequest
import com.veyq.app.generated.model.UpdateProfileRequest
import com.veyq.app.shared.security.REFRESH_SESSION_COOKIE
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.security.clearRefreshSessionCookie
import com.veyq.app.shared.security.refreshSessionCookie
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated LoginApi; delegates to the handler. */
@ApplicationScoped
class LoginResource(
    private val handler: LoginHandler,
    private val sessions: SessionService,
) : LoginApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    override fun login(loginRequest: LoginRequest): Response {
        val result = handler.login(loginRequest)
        return Response.ok(result.response)
            .header("Set-Cookie", refreshSessionCookie(result.issuedSession))
            .build()
    }

    override fun getCurrentUser(authorization: String): Response =
        Response.ok(handler.currentUser(authorization)).build()

    override fun updateCurrentProfile(authorization: String, updateProfileRequest: UpdateProfileRequest): Response =
        Response.ok(handler.updateProfile(authorization, updateProfileRequest)).build()

    override fun getCurrentAvatar(authorization: String): Response {
        val avatar = handler.currentAvatar(authorization)
        return Response.ok(avatar.bytes, avatar.contentType).build()
    }

    override fun updateCurrentAvatar(authorization: String, updateAvatarRequest: UpdateAvatarRequest): Response =
        Response.ok(handler.updateAvatar(authorization, updateAvatarRequest)).build()

    override fun deleteCurrentAvatar(authorization: String): Response =
        Response.ok(handler.deleteAvatar(authorization)).build()

    override fun changeCurrentPassword(authorization: String, changePasswordRequest: ChangePasswordRequest): Response {
        handler.changePassword(authorization, changePasswordRequest)
        return Response.noContent()
            .header("Set-Cookie", clearRefreshSessionCookie())
            .build()
    }

    override fun deleteCurrentAccount(authorization: String, deleteAccountRequest: DeleteAccountRequest): Response {
        handler.deleteAccount(authorization, deleteAccountRequest)
        return Response.noContent()
            .header("Set-Cookie", clearRefreshSessionCookie())
            .build()
    }

    override fun getPersonalScope(authorization: String): Response =
        Response.ok(handler.personalScope(authorization)).build()

    override fun logout(authorization: String): Response {
        handler.logout(authorization, httpHeaders.cookies[REFRESH_SESSION_COOKIE]?.value)
        return Response.noContent()
            .header("Set-Cookie", clearRefreshSessionCookie())
            .build()
    }

    override fun refreshSession(): Response {
        val refreshToken = httpHeaders.cookies[REFRESH_SESSION_COOKIE]?.value
        val issued = sessions.refresh(refreshToken)
            ?: return Response.status(Response.Status.UNAUTHORIZED)
                .header("Set-Cookie", clearRefreshSessionCookie())
                .build()
        return Response.ok(RefreshSessionResponse(sessionToken = issued.accessToken))
            .header("Set-Cookie", refreshSessionCookie(issued))
            .build()
    }
}
