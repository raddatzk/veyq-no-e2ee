package com.veyq.app.features.account.resetpassword

import com.veyq.app.features.account.login.LoginHandler
import com.veyq.app.generated.api.PasswordApi
import com.veyq.app.generated.model.ChangePasswordRequest
import com.veyq.app.generated.model.PasswordResetConfirmRequest
import com.veyq.app.generated.model.PasswordResetRequestRequest
import com.veyq.app.generated.model.PasswordResetTokenRequest
import com.veyq.app.shared.security.clearRefreshSessionCookie
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

@ApplicationScoped
class PasswordResetResource(
    private val handler: PasswordResetHandler,
    private val loginHandler: LoginHandler,
) : PasswordApi {
    @Context
    lateinit var httpHeaders: HttpHeaders

    override fun changeCurrentPassword(changePasswordRequest: ChangePasswordRequest): Response {
        loginHandler.changePassword(authorization(), changePasswordRequest)
        return Response.noContent()
            .header("Set-Cookie", clearRefreshSessionCookie())
            .build()
    }

    override fun requestPasswordReset(passwordResetRequestRequest: PasswordResetRequestRequest): Response {
        handler.requestReset(passwordResetRequestRequest)
        return Response.noContent().build()
    }

    override fun getPasswordResetMaterial(passwordResetTokenRequest: PasswordResetTokenRequest): Response =
        Response.ok(handler.getMaterial(passwordResetTokenRequest)).build()

    override fun confirmPasswordReset(passwordResetConfirmRequest: PasswordResetConfirmRequest): Response {
        handler.confirm(passwordResetConfirmRequest)
        return Response.noContent().build()
    }

    private fun authorization(): String? = httpHeaders.getHeaderString(HttpHeaders.AUTHORIZATION)
}
