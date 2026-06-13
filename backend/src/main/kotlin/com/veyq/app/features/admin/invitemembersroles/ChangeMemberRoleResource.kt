package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.MemberRoleApi
import com.veyq.app.generated.model.ChangeMemberRoleRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated MemberRoleApi; delegates to the handler. */
@ApplicationScoped
class ChangeMemberRoleResource(private val handler: ChangeMemberRoleHandler) : MemberRoleApi {

    override fun changeMemberRole(authorization: String, tenantId: String, userId: String, changeMemberRoleRequest: ChangeMemberRoleRequest): Response =
        Response.ok(handler.changeMemberRole(authorization, tenantId, userId, changeMemberRoleRequest)).build()
}
