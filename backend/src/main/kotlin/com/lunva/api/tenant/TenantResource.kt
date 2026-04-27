package com.lunva.api.tenant

import com.lunva.api.domain.ServerRole
import com.lunva.api.security.ServerRolesRequired
import com.lunva.api.user.UserSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType

data class CreateTenantRequest(
    @field:NotBlank(message = "name must not be blank") val name: String?,
    @field:NotBlank(message = "displayName must not be blank") val displayName: String?,
)

data class TenantSummary(val id: Long, val name: String, val displayName: String, val admins: List<UserSummary>)

data class TenantsSummary(val tenants: List<TenantSummary>)

@Path("/api")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class TenantResource @Inject constructor(
    private val tenantService: TenantService,
) {

    @GET
    @Path("/tenants")
    fun myTenants(): TenantsSummary =
        tenantService.listMyTenants()

    @POST
    @Path("/tenants")
    @ServerRolesRequired(ServerRole.SERVER_OWNER)
    fun createTenant(@Valid request: CreateTenantRequest): TenantSummary =
        tenantService.createTenantForCurrentUser(request)
}
