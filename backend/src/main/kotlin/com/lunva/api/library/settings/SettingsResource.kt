package com.lunva.api.library.settings

import com.lunva.api.auth.filterchain.GroupOwnerRequired
import com.lunva.api.auth.filterchain.TenantOwnerRequired
import com.lunva.api.generated.settings.api.SettingsApi
import com.lunva.api.generated.settings.model.SettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@ApplicationScoped
@Path("/api/settings")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class SettingsResource @Inject constructor(
    private val settingsService: SettingsService,
) : SettingsApi {
    override fun getSettings(path: String): SettingsResponse =
        settingsService.settings(path)

    @GET
    @Path("/server")
    fun getServerSettings(): ServerSettingsResponse =
        settingsService.serverSettings()

    @GET
    @Path("/me")
    fun getMySettings(): UserSettingsResponse =
        settingsService.mySettings()

    @PUT
    @Path("/me")
    fun updateMySettings(request: UpdateUserSettingsRequest): UserSettingsResponse =
        settingsService.updateMySettings(request)

    @POST
    @Path("/me/password")
    fun changeMyPassword(request: ChangePasswordRequest): Response {
        settingsService.changeMyPassword(request)
        return Response.noContent().build()
    }

    @GET
    @Path("/{tenantName}")
    fun getTenantSettings(@PathParam("tenantName") tenantName: String): TenantSettingsResponse =
        settingsService.tenantSettings(tenantName)

    @PUT
    @Path("/{tenantName}")
    @TenantOwnerRequired
    fun updateTenantSettings(
        @PathParam("tenantName") tenantName: String,
        request: UpdateNamedSettingsRequest,
    ): TenantSettingsResponse =
        settingsService.updateTenantSettings(tenantName, request)

    @GET
    @Path("/{tenantName}/me")
    fun getTenantUserSettings(@PathParam("tenantName") tenantName: String): TenantUserSettingsResponse =
        settingsService.tenantUserSettings(tenantName)

    @PUT
    @Path("/{tenantName}/me")
    fun updateTenantUserSettings(@PathParam("tenantName") tenantName: String): TenantUserSettingsResponse =
        settingsService.tenantUserSettings(tenantName)

    @GET
    @Path("/{tenantName}/{groupName}")
    fun getGroupSettings(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String,
    ): GroupSettingsResponse =
        settingsService.groupSettings(tenantName, groupName)

    @PUT
    @Path("/{tenantName}/{groupName}")
    @GroupOwnerRequired
    fun updateGroupSettings(
        @PathParam("tenantName") tenantName: String,
        @PathParam("groupName") groupName: String,
        request: UpdateNamedSettingsRequest,
    ): GroupSettingsResponse =
        settingsService.updateGroupSettings(tenantName, groupName, request)
}
