package com.lunva.api.usermgmt

import com.lunva.api.auth.filterchain.GroupOwnerRequired
import com.lunva.api.generated.groups.api.GroupsApi
import com.lunva.api.generated.groups.model.CreateGroupRequest
import com.lunva.api.generated.groups.model.GroupSummary
import com.lunva.api.generated.groups.model.GroupsSummary
import com.lunva.api.generated.groups.model.UpdateGroupRequest
import com.lunva.api.tenant.TenantService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.PathParam

@ApplicationScoped
class GroupResource @Inject constructor(
    private val tenantService: TenantService,
    private val groupService: GroupService,
) : GroupsApi {

    override fun getGroups(tenantName: String): GroupsSummary {
        return groupService.getGroups()
    }

    override fun getGroup(
        tenantName: String,
        groupName: String): GroupSummary {
        return groupService.getGroup(groupName)
    }

    override fun createGroup(
        tenantName: String,
        request: CreateGroupRequest,
    ): GroupSummary {
        return groupService.createGroup(request)
    }

    @GroupOwnerRequired
    override fun updateGroup(
        tenantName: String,
        @PathParam("groupName") groupName: String,
        request: UpdateGroupRequest,
    ): GroupSummary {
        return groupService.updateGroup(groupName, request)
    }
}
