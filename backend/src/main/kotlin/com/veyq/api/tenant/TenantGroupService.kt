package com.veyq.api.tenant

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.TenantGroup
import com.veyq.api.domain.TenantGroupMemberRepo
import com.veyq.api.domain.TenantGroupRepo
import com.veyq.api.generated.model.TenantGroupSettingsResponse
import com.veyq.api.generated.model.UpdateGroupSettingsRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class TenantGroupService @Inject constructor(
    private val tenantGroupRepo: TenantGroupRepo,
    private val requestContext: RequestContext,
    private val tenantGroupMemberRepo: TenantGroupMemberRepo,
) {
    fun groupSettings(tenantName: String, groupName: String): TenantGroupSettingsResponse {
        val group = tenantGroup(groupName)
        requireGroupMembership(group)
        return TenantGroupSettingsResponse(group.id, group.name, group.displayName)
    }

    @Transactional
    fun updateGroupSettings(tenantName: String, groupName: String, request: UpdateGroupSettingsRequest): TenantGroupSettingsResponse {
        val group = tenantGroup(groupName)
        group.displayName = request.displayName.trim()
        return TenantGroupSettingsResponse(group.id, group.name, group.displayName)
    }

    private fun tenantGroup(groupName: String) =
        tenantGroupRepo.findByTenantAndName(tenant(), groupName)
            ?: throw NotFoundException("Group $groupName not found")

    private fun requireGroupMembership(group: TenantGroup) {
        tenantGroupMemberRepo.findByGroupAndUser(group, user())
            ?: throw NotFoundException("Group $group.name not found")
    }

    private fun tenant() = requestContext.requireTenant()
    private fun user() = requestContext.requireUser()
}
