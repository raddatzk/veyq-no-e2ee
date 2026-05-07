package com.lunva.api.usermgmt.group

import com.lunva.api.auth.filterchain.GroupOwnerRequired
import com.lunva.api.generated.api.GroupsApi
import com.lunva.api.generated.model.CreateGroupRequest
import com.lunva.api.generated.model.GroupSummary
import com.lunva.api.generated.model.GroupsSummary
import com.lunva.api.generated.model.UpdateGroupRequest
import com.lunva.api.tenant.TenantService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
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
    @Transactional
    override fun deleteGroup(
        tenantName: String,
        @PathParam("groupName") groupName: String,
    ) {
        groupService.deleteGroup(groupName)
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
