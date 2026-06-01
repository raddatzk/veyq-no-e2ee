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
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@ApplicationScoped
class TenantResource @Inject constructor(
    private val tenantService: TenantService,
    private val tenantUserService: TenantUserService,
    private val tenantGroupService: TenantGroupService,
) : TenantsApi {

    override fun myTenants(): TenantsSummary =
        tenantService.listMyTenants()

    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun createTenant(request: CreateTenantRequest): TenantCreationResponse =
        tenantService.createTenantForInvitedOwner(request)


    override fun getTenantSettings(tenantName: String): TenantSettingsResponse =
        tenantService.tenantSettings()

    @GET
    @Path("/{tenantName}/logo")
    @Produces("image/png", "image/jpeg")
    override fun getTenantLogo(@PathParam("tenantName") tenantName: String): File =
        tenantService.tenantLogo()

    @TenantOwnerRequired
    @PUT
    @Path("/{tenantName}/logo")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    override fun updateTenantLogo(
        @PathParam("tenantName") tenantName: String,
        @FormParam("file") fileInputStream: InputStream,
    ): TenantSettingsResponse {
        val temp = Files.createTempFile("veyq-tenant-logo-", ".tmp")
        return try {
            fileInputStream.use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            tenantService.updateTenantLogo(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @TenantOwnerRequired
    @DELETE
    @Path("/{tenantName}/logo")
    @Produces(MediaType.APPLICATION_JSON)
    override fun deleteTenantLogo(@PathParam("tenantName") tenantName: String): TenantSettingsResponse =
        tenantService.deleteTenantLogo()

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

    @TenantOwnerRequired
    @DELETE
    @Path("/{tenantName}/users/{userId}")
    override fun removeTenantUser(
        @PathParam("tenantName") tenantName: String,
        @PathParam("userId") userId: Long,
    ) {
        tenantUserService.removeTenantUser(tenantName, userId)
    }

    @GET
    @Path("/{tenantName}/{groupName:(?!logo$)[^/]+}")
    @Produces(MediaType.APPLICATION_JSON)
    override fun getGroupSettings(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String,
    ): TenantGroupSettingsResponse =
        tenantGroupService.groupSettings(tenantName, groupName)

    @GroupOwnerRequired
    @Transactional
    @PUT
    @Path("/{tenantName}/{groupName:(?!logo$)[^/]+}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    override fun updateGroupSettings(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String,
        request: UpdateGroupSettingsRequest,
    ): TenantGroupSettingsResponse =
        tenantGroupService.updateGroupSettings(tenantName, groupName, request)
}
