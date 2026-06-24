package com.veyq.app.features.admin.invitemembersroles

import com.veyq.app.generated.api.TenantMembersApi
import com.veyq.app.generated.model.ChangeMemberRoleRequest
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing the generated TenantMembersApi; delegates to member handlers. */
@ApplicationScoped
class TenantMembersResource(
    private val memberList: MemberListHandler,
    private val removals: RemoveMemberHandler,
    private val changeRole: ChangeMemberRoleHandler,
    private val currentTenant: CurrentTenantResolver,
) : TenantMembersApi {

    override fun listTenantMembers(authorization: String): Response =
        Response.ok(memberList.listTenantMembers(authorization, currentTenant.currentTenantId())).build()

    override fun removeMember(authorization: String, userId: String): Response =
        Response.ok(removals.removeMember(authorization, currentTenant.currentTenantId(), userId)).build()

    override fun changeMemberRole(authorization: String, userId: String, changeMemberRoleRequest: ChangeMemberRoleRequest): Response =
        Response.ok(changeRole.changeMemberRole(authorization, currentTenant.currentTenantId(), userId, changeMemberRoleRequest)).build()
}
