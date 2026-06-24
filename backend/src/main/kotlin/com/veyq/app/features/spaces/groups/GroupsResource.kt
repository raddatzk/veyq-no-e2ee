package com.veyq.app.features.spaces.groups

import com.veyq.app.generated.api.GroupLifecycleApi
import com.veyq.app.generated.api.GroupMembersApi
import com.veyq.app.generated.model.AddGroupMemberRequest
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.SetGroupRoleRequest
import com.veyq.app.shared.tenancy.CurrentTenantResolver
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for generated group APIs; delegates after resolving the current tenant from the host. */
@ApplicationScoped
class GroupsResource(
    private val handler: GroupProvisioningHandler,
    private val currentTenant: CurrentTenantResolver,
) : GroupLifecycleApi {
    override fun listGroups(authorization: String): Response =
        Response.ok(handler.listGroups(authorization, currentTenant.currentTenantId())).build()

    override fun createGroup(authorization: String, createGroupRequest: CreateGroupRequest): Response =
        Response.ok(handler.createGroup(authorization, currentTenant.currentTenantId(), createGroupRequest)).build()

    override fun deleteGroup(authorization: String, groupId: String): Response {
        handler.deleteGroup(authorization, currentTenant.currentTenantId(), groupId)
        return Response.noContent().build()
    }
}

@ApplicationScoped
class GroupMembersResource(
    private val handler: GroupProvisioningHandler,
    private val currentTenant: CurrentTenantResolver,
) : GroupMembersApi {
    override fun listGroupMembers(authorization: String, groupId: String): Response =
        Response.ok(handler.listGroupMembers(authorization, currentTenant.currentTenantId(), groupId)).build()

    override fun addGroupMember(authorization: String, groupId: String, addGroupMemberRequest: AddGroupMemberRequest): Response =
        Response.ok(handler.addGroupMember(authorization, currentTenant.currentTenantId(), groupId, addGroupMemberRequest)).build()

    override fun setGroupMemberRole(authorization: String, groupId: String, userId: String, setGroupRoleRequest: SetGroupRoleRequest): Response =
        Response.ok(handler.setGroupMemberRole(authorization, currentTenant.currentTenantId(), groupId, userId, setGroupRoleRequest)).build()

    override fun removeGroupMember(authorization: String, groupId: String, userId: String): Response {
        handler.removeGroupMember(authorization, currentTenant.currentTenantId(), groupId, userId)
        return Response.noContent().build()
    }
}
