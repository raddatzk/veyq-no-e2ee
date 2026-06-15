package com.veyq.app.features.spaces.groups

import com.veyq.app.generated.api.GroupsApi
import com.veyq.app.generated.model.AddGroupMemberRequest
import com.veyq.app.generated.model.CreateGroupRequest
import com.veyq.app.generated.model.SetGroupRoleRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated GroupsApi (groups + their members under /api/tenants/{tenantId}); delegates. */
@ApplicationScoped
class GroupsResource(private val handler: GroupProvisioningHandler) : GroupsApi {
    override fun listGroups(authorization: String, tenantId: String): Response =
        Response.ok(handler.listGroups(authorization, tenantId)).build()

    override fun createGroup(authorization: String, tenantId: String, createGroupRequest: CreateGroupRequest): Response =
        Response.ok(handler.createGroup(authorization, tenantId, createGroupRequest)).build()

    override fun listGroupMembers(authorization: String, tenantId: String, scopeId: String): Response =
        Response.ok(handler.listGroupMembers(authorization, tenantId, scopeId)).build()

    override fun addGroupMember(authorization: String, tenantId: String, scopeId: String, addGroupMemberRequest: AddGroupMemberRequest): Response =
        Response.ok(handler.addGroupMember(authorization, tenantId, scopeId, addGroupMemberRequest)).build()

    override fun setGroupMemberRole(authorization: String, tenantId: String, scopeId: String, userId: String, setGroupRoleRequest: SetGroupRoleRequest): Response =
        Response.ok(handler.setGroupMemberRole(authorization, tenantId, scopeId, userId, setGroupRoleRequest)).build()

    override fun removeGroupMember(authorization: String, tenantId: String, scopeId: String, userId: String): Response {
        handler.removeGroupMember(authorization, tenantId, scopeId, userId)
        return Response.noContent().build()
    }
}
