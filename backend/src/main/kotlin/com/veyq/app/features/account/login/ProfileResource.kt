package com.veyq.app.features.account.login

import com.veyq.app.generated.api.ProfilesApi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Tenant-gated profile metadata. It never exposes a plaintext Memory-participant graph. */
@ApplicationScoped
class ProfileResource(private val handler: LoginHandler) : ProfilesApi {
    override fun getUserProfile(authorization: String, tenantName: String, userPermalink: String): Response =
        Response.ok(handler.publicProfile(authorization, tenantName, userPermalink)).build()

    override fun getUserProfileAvatar(authorization: String, tenantName: String, userPermalink: String): Response {
        val avatar = handler.publicAvatar(authorization, tenantName, userPermalink)
        return Response.ok(avatar.bytes, avatar.contentType).build()
    }
}
