package com.veyq.app.features.spaces.groups

import com.veyq.app.generated.api.GroupsApi
import com.veyq.app.generated.model.CreateGroupRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry for the generated GroupsApi (GET/POST /api/tenants/{tenantId}/groups); delegates. */
@ApplicationScoped
class GroupsResource(private val handler: GroupProvisioningHandler) : GroupsApi {
    override fun listGroups(authorization: String, tenantId: String): Response =
        Response.ok(handler.listGroups(authorization, tenantId)).build()

    override fun createGroup(authorization: String, tenantId: String, createGroupRequest: CreateGroupRequest): Response =
        Response.ok(handler.createGroup(authorization, tenantId, createGroupRequest)).build()
}
