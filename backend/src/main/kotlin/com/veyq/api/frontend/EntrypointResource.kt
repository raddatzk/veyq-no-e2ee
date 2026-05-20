package com.veyq.api.frontend

import com.veyq.api.generated.api.FrontendApi
import com.veyq.api.generated.model.BootstrapResponse
import com.veyq.api.setup.ServerState
import com.veyq.api.tenant.TenantService
import com.veyq.api.usermgmt.user.UserService
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
