package com.veyq.app.features.account.login

import com.veyq.app.generated.api.AccountLifecycleApi
import com.veyq.app.generated.api.AvatarApi
import com.veyq.app.generated.api.SessionApi
import com.veyq.app.generated.model.CompleteAvatarUploadRequest
import com.veyq.app.generated.model.DeleteAccountRequest
import com.veyq.app.generated.model.LoginSessionList
import com.veyq.app.generated.model.LoginSessionSummary
import com.veyq.app.generated.model.LoginRequest
import com.veyq.app.generated.model.RefreshSessionResponse
import com.veyq.app.generated.model.RegisterViaInvitationRequest
import com.veyq.app.generated.model.RenameLoginSessionRequest
import com.veyq.app.generated.model.StartAvatarUploadRequest
import com.veyq.app.generated.model.UpdateProfileRequest
import com.veyq.app.features.account.registerviainvitation.RegisterViaInvitationHandler
import com.veyq.app.platform.error.ApiException
import com.veyq.app.platform.error.unauthorized
import com.veyq.app.shared.security.REFRESH_SESSION_COOKIE
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.security.clearRefreshSessionCookie
import com.veyq.app.shared.security.refreshSessionCookie
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Thin REST entry implementing generated session APIs; delegates to the handler. */
@ApplicationScoped
class LoginResource(
    private val handler: LoginHandler,
    private val sessions: SessionService,
) : SessionApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    override fun login(loginRequest: LoginRequest): Response {
        val result = handler.login(loginRequest)
        return Response.ok(result.response)
            .header("Set-Cookie", refreshSessionCookie(result.issuedSession))
            .build()
    }

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

    override fun listLoginSessions(authorization: String): Response {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        return Response.ok(
            LoginSessionList(
                sessions = sessions.listLoginSessions(userId).map {
                    LoginSessionSummary(
                        rootSessionId = it.rootSessionId,
                        encryptedLabel = it.encryptedLabel,
                        loginMethod = LoginSessionSummary.LoginMethod.valueOf(it.loginMethod),
                        createdAt = OffsetDateTime.ofInstant(it.createdAt, ZoneOffset.UTC),
                        lastUsedAt = OffsetDateTime.ofInstant(it.lastUsedAt, ZoneOffset.UTC),
                    )
                },
            ),
        ).build()
    }

    override fun renameLoginSession(
        authorization: String,
        rootSessionId: String,
        renameLoginSessionRequest: RenameLoginSessionRequest,
    ): Response {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        val encryptedLabel = renameLoginSessionRequest.encryptedLabel.trim()
        if (encryptedLabel.isBlank()) throw ApiException(400, "SESSION_LABEL_REQUIRED", "Encrypted session label is required")
        sessions.renameLoginSession(userId, rootSessionId, encryptedLabel)
        return Response.noContent().build()
    }

    override fun removeLoginSession(authorization: String, rootSessionId: String): Response {
        val userId = sessions.resolve(authorization) ?: throw unauthorized()
        sessions.invalidateLoginSession(userId, rootSessionId)
        return Response.noContent().build()
    }
}

@ApplicationScoped
class AccountLifecycleResource(
    private val handler: LoginHandler,
    private val registration: RegisterViaInvitationHandler,
) : AccountLifecycleApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    override fun getCurrentUser(): Response =
        Response.ok(handler.currentUser(authorization())).build()

    override fun updateCurrentProfile(updateProfileRequest: UpdateProfileRequest): Response =
        Response.ok(handler.updateProfile(authorization(), updateProfileRequest)).build()

    override fun deleteCurrentAccount(deleteAccountRequest: DeleteAccountRequest): Response {
        handler.deleteAccount(authorization(), deleteAccountRequest)
        return Response.noContent()
            .header("Set-Cookie", clearRefreshSessionCookie())
            .build()
    }

    override fun registerViaInvitation(inviteId: String, registerViaInvitationRequest: RegisterViaInvitationRequest): Response {
        val result = registration.registerViaInvitation(inviteId, registerViaInvitationRequest)
        return Response.ok(result.response)
            .header("Set-Cookie", refreshSessionCookie(result.issuedSession))
            .build()
    }

    private fun authorization(): String? = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION)
}

@ApplicationScoped
class AvatarResource(private val handler: LoginHandler) : AvatarApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    override fun startCurrentAvatarUpload(startAvatarUploadRequest: StartAvatarUploadRequest): Response =
        Response.ok(handler.startAvatarUpload(authorization(), startAvatarUploadRequest)).build()

    override fun completeCurrentAvatarUpload(completeAvatarUploadRequest: CompleteAvatarUploadRequest): Response =
        Response.ok(handler.completeAvatarUpload(authorization(), completeAvatarUploadRequest)).build()

    override fun deleteCurrentAvatar(): Response =
        Response.ok(handler.deleteAvatar(authorization())).build()

    private fun authorization(): String? = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION)
}
