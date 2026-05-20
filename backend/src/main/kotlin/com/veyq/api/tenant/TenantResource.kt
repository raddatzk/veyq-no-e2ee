package com.veyq.api.tenant

import com.veyq.api.auth.filterchain.GroupOwnerRequired
import com.veyq.api.auth.filterchain.ServerRolesRequired
import com.veyq.api.auth.filterchain.TenantOwnerRequired
import com.veyq.api.domain.ServerRole
import com.veyq.api.generated.api.TenantsApi
import com.veyq.api.generated.model.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.PathParam

@ApplicationScoped
class TenantResource @Inject constructor(
    private val tenantService: TenantService,
    private val tenantUserService: TenantUserService,
    private val tenantGroupService: TenantGroupService,
) : TenantsApi {

    override fun myTenants(): TenantsSummary =
        tenantService.listMyTenants()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun createTenant(request: CreateTenantRequest): TenantSummary =
        tenantService.createTenantForCurrentUser(request)


    override fun getTenantSettings(tenantName: String): TenantSettingsResponse =
        tenantService.tenantSettings()

    @TenantOwnerRequired
    @Transactional
    override fun deleteTenant(tenantName: String) {
        tenantService.deleteTenant()
    }

    @TenantOwnerRequired
    @Transactional
    override fun updateTenantSettings(
        tenantName: String,
        request: UpdateTenantSettingsRequest,
    ): TenantSettingsResponse =
        tenantService.updateTenantSettings(request)

    override fun getTenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        tenantUserService.tenantUserSettings(tenantName)

    override fun updateTenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        tenantUserService.tenantUserSettings(tenantName)

    override fun tenantUsers(tenantName: String): TenantUsersSummary =
        tenantUserService.tenantUsers(tenantName)

    @TenantOwnerRequired
    override fun inviteTenantUser(
        tenantName: String,
        request: InviteTenantUserRequest,
    ): TenantUserSummary =
        tenantUserService.inviteTenantUser(tenantName, request)

    @TenantOwnerRequired
    override fun reinviteTenantUser(tenantName: String, userId: Long): TenantUserSummary =
        tenantUserService.reinviteTenantUser(tenantName, userId)

    @TenantOwnerRequired
    override fun updateTenantUserRole(
        tenantName: String,
        userId: Long,
        request: UpdateTenantUserRoleRequest,
    ): TenantUserSummary =
        tenantUserService.updateTenantUserRole(tenantName, userId, request)

    override fun getGroupSettings(
        tenantName: String,
        groupName: String,
    ): TenantGroupSettingsResponse =
        tenantGroupService.groupSettings(tenantName, groupName)

    @GroupOwnerRequired
    @Transactional
    override fun updateGroupSettings(
        tenantName: String,
        @PathParam("groupName") groupName: String,
        request: UpdateGroupSettingsRequest,
    ): TenantGroupSettingsResponse =
        tenantGroupService.updateGroupSettings(tenantName, groupName, request)
}
