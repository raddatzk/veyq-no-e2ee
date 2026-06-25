package com.veyq.app.features.account.login

import com.veyq.app.generated.api.ProfilesApi
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Tenant-gated profile metadata. It never exposes a plaintext Memory-participant graph. */
@ApplicationScoped
class ProfileResource(
    private val handler: LoginHandler,
    private val currentTenant: CurrentTenantResolver,
) : ProfilesApi {
    override fun getUserProfile(authorization: String, username: String): Response =
        Response.ok(handler.publicProfile(authorization, currentTenant.currentTenantId(), username)).build()
}
