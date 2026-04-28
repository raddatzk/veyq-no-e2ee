package com.lunva.api.frontend

import com.lunva.api.tenant.TenantService
import com.lunva.api.tenant.TenantSummary
import com.lunva.api.usermgmt.UserService
import com.lunva.api.usermgmt.UserSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

data class EntrypointSummary(
    val user: UserSummary?,
    val tenants: List<TenantSummary>
)

@Path("/api/frontend/bootstrap")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class EntrypointResource @Inject constructor(
    private val tenantService: TenantService,
    private val userService: UserService,
) {

    @GET
    fun entrypoint(): EntrypointSummary =
        EntrypointSummary(
            userService.me(),
            tenantService.listMyTenants().tenants
        )
}