package com.lunva.api.frontend

import com.lunva.api.setup.ServerState
import com.lunva.api.generated.tenants.model.TenantSummary
import com.lunva.api.generated.users.model.UserSummary
import com.lunva.api.tenant.TenantService
import com.lunva.api.usermgmt.UserService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

data class EntrypointSummary(
    val serverId: String,
    val user: UserSummary?,
    val tenants: List<TenantSummary>
)

@Path("/api/frontend/bootstrap")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class EntrypointResource @Inject constructor(
    private val tenantService: TenantService,
    private val userService: UserService,
    private val serverState: ServerState
) {

    @GET
    fun entrypoint(): EntrypointSummary =
        EntrypointSummary(
            serverState.serverId,
            userService.me(),
            tenantService.listMyTenants().tenants
        )
}
