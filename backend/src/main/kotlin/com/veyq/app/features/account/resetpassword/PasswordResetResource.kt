package com.veyq.app.features.account.resetpassword

import com.veyq.app.generated.api.PasswordResetConfirmApi
import com.veyq.app.generated.api.PasswordResetMaterialApi
import com.veyq.app.generated.api.PasswordResetRequestApi
import com.veyq.app.generated.model.PasswordResetConfirmRequest
import com.veyq.app.generated.model.PasswordResetRequestRequest
import com.veyq.app.generated.model.PasswordResetTokenRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

// Each generated tag carries its own class-level @Path, so each gets its own Resource (a single class
// implementing all three would apply every class-@Path to every method → duplicate route declarations).

@ApplicationScoped
class PasswordResetRequestResource(private val handler: PasswordResetHandler) : PasswordResetRequestApi {
    override fun requestPasswordReset(passwordResetRequestRequest: PasswordResetRequestRequest): Response {
        handler.requestReset(passwordResetRequestRequest)
        return Response.noContent().build()
    }
}

@ApplicationScoped
class PasswordResetMaterialResource(private val handler: PasswordResetHandler) : PasswordResetMaterialApi {
    override fun getPasswordResetMaterial(passwordResetTokenRequest: PasswordResetTokenRequest): Response =
        Response.ok(handler.getMaterial(passwordResetTokenRequest)).build()
}

@ApplicationScoped
class PasswordResetConfirmResource(private val handler: PasswordResetHandler) : PasswordResetConfirmApi {
    override fun confirmPasswordReset(passwordResetConfirmRequest: PasswordResetConfirmRequest): Response {
        handler.confirm(passwordResetConfirmRequest)
        return Response.noContent().build()
    }
}
