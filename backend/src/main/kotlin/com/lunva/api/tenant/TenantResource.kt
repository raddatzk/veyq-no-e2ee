package com.lunva.api.tenant

import com.lunva.api.auth.filterchain.GroupOwnerRequired
import com.lunva.api.auth.filterchain.ServerRolesRequired
import com.lunva.api.auth.filterchain.TenantOwnerRequired
import com.lunva.api.domain.ServerRole
import com.lunva.api.generated.tenants.api.TenantsApi
import com.lunva.api.generated.tenants.model.CreateTenantRequest
import com.lunva.api.generated.tenants.model.GroupSettingsResponse
import com.lunva.api.generated.tenants.model.TenantSettingsResponse
import com.lunva.api.generated.tenants.model.TenantSummary
import com.lunva.api.generated.tenants.model.TenantUserSettingsResponse
import com.lunva.api.generated.tenants.model.TenantsSummary
import com.lunva.api.generated.tenants.model.UpdateNamedSettingsRequest
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
    override fun updateTenantSettings(
        tenantName: String,
        request: UpdateNamedSettingsRequest,
    ): TenantSettingsResponse =
        tenantService.updateTenantSettings(request)

    override fun getTenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        tenantUserService.tenantUserSettings(tenantName)

    override fun updateTenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        tenantUserService.tenantUserSettings(tenantName)

    override fun getGroupSettings(
        tenantName: String,
        groupName: String,
    ): GroupSettingsResponse =
        tenantGroupService.groupSettings(tenantName, groupName)

    @GroupOwnerRequired
    @Transactional
    override fun updateGroupSettings(
        tenantName: String,
        @PathParam("groupName") groupName: String,
        request: UpdateNamedSettingsRequest,
    ): GroupSettingsResponse =
        tenantGroupService.updateGroupSettings(tenantName, groupName, request)
}
