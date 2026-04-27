package com.lunva.api.bootstrap

import com.lunva.api.setup.SetupStatusResponse
import com.lunva.api.setup.SetupService
import com.lunva.api.tenant.TenantService
import com.lunva.api.tenant.TenantSummary
import com.lunva.api.user.UserService
import com.lunva.api.user.UserSummary
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

data class BootstrapSummary(
    val adminRequired: Boolean,
    val tenantAvailable: Boolean,
    val user: UserSummary?,
    val tenants: List<TenantSummary>,
)

@Path("/api/bootstrap")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class BootstrapResource @Inject constructor(
    private val setupService: SetupService,
    private val userService: UserService,
    private val tenantService: TenantService,
    private val identity: SecurityIdentity,
) {

    @GET
    fun bootstrap(): BootstrapSummary {
        val setup: SetupStatusResponse = setupService.status()
        if (identity.isAnonymous) {
            return BootstrapSummary(setup.adminRequired, setup.tenantAvailable, null, emptyList())
        }

        return BootstrapSummary(
            setup.adminRequired,
            setup.tenantAvailable,
            userService.me(),
            tenantService.listMyTenants().tenants,
        )
    }
}
