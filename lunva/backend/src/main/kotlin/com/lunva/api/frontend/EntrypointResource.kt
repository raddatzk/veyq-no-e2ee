package com.lunva.api.frontend

import com.lunva.api.generated.api.FrontendApi
import com.lunva.api.generated.model.BootstrapResponse
import com.lunva.api.setup.ServerState
import com.lunva.api.tenant.TenantService
import com.lunva.api.usermgmt.user.UserService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/frontend/bootstrap")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class EntrypointResource @Inject constructor(
    private val tenantService: TenantService,
    private val userService: UserService,
    private val serverState: ServerState
) : FrontendApi {

    override fun entrypoint(): BootstrapResponse =
        BootstrapResponse(
            serverState.serverId,
            userService.me(),
            tenantService.listMyTenants().tenants
        )
}
