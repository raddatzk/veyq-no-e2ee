package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.TenantScopeApi
import com.veyq.app.generated.model.RotateScopeRequest
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated TenantScopeApi; delegates to scope handlers. */
@ApplicationScoped
class TenantScopeResource(
    private val rotation: ScopeRotationHandler,
    private val currentTenant: CurrentTenantResolver,
) : TenantScopeApi {

    override fun rotateScope(authorization: String, rotateScopeRequest: RotateScopeRequest): Response =
        Response.ok(rotation.rotateScope(authorization, currentTenant.currentTenantId(), rotateScopeRequest)).build()
}
