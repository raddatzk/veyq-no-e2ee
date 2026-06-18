package com.veyq.app.features.account.login

import com.veyq.app.generated.api.LoginApi
import com.veyq.app.generated.model.LoginRequest
import com.veyq.app.generated.model.UpdateProfileRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated LoginApi; delegates to the handler. */
@ApplicationScoped
class LoginResource(private val handler: LoginHandler) : LoginApi {

    override fun login(loginRequest: LoginRequest): Response =
        Response.ok(handler.login(loginRequest)).build()

    override fun getCurrentUser(authorization: String): Response =
        Response.ok(handler.currentUser(authorization)).build()

    override fun updateCurrentProfile(authorization: String, updateProfileRequest: UpdateProfileRequest): Response =
        Response.ok(handler.updateProfile(authorization, updateProfileRequest)).build()

    override fun getPersonalScope(authorization: String): Response =
        Response.ok(handler.personalScope(authorization)).build()

    override fun logout(authorization: String): Response {
        handler.logout(authorization)
        return Response.noContent().build()
    }
}
