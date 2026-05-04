package com.lunva.api.tenant

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.TenantGroup
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.generated.tenants.model.GroupSettingsResponse
import com.lunva.api.generated.tenants.model.UpdateNamedSettingsRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class TenantGroupService @Inject constructor(
    private val tenantGroupRepo: TenantGroupRepo,
    private val requestContext: RequestContext,
    private val tenantGroupMemberRepo: TenantGroupMemberRepo,
) {
    fun groupSettings(tenantName: String, groupName: String): GroupSettingsResponse {
        val group = tenantGroup(groupName)
        requireGroupMembership(group)
        return GroupSettingsResponse(group.id, group.name, group.displayName)
    }

    @Transactional
    fun updateGroupSettings(tenantName: String, groupName: String, request: UpdateNamedSettingsRequest): GroupSettingsResponse {
        val group = tenantGroup(groupName)
        group.displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("displayName must not be blank")
        return GroupSettingsResponse(group.id, group.name, group.displayName)
    }

    private fun tenantGroup(groupName: String) =
        tenantGroupRepo.findByTenantAndName(tenant(), groupName)
            ?: throw NotFoundException("Group $groupName not found")

    private fun requireGroupMembership(group: TenantGroup) {
        tenantGroupMemberRepo.findByGroupAndUser(group, user())
            ?: throw NotFoundException("Group $group.name not found")
    }

    private fun tenant() = requestContext.tenant!!
    private fun user() = requestContext.user!!
}
