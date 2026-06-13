package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.TenantMembersApi
import com.veyq.app.generated.model.GrantScopeAccessRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated TenantMembersApi; delegates to the grant handler. */
@ApplicationScoped
class MemberAccessResource(private val handler: GrantScopeAccessHandler) : TenantMembersApi {

    override fun grantScopeAccess(authorization: String, tenantId: String, grantScopeAccessRequest: GrantScopeAccessRequest): Response =
        Response.ok(handler.grantScopeAccess(authorization, tenantId, grantScopeAccessRequest)).build()
}
