package com.lunva.api.security

import com.lunva.api.tenant.TenantService
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION)
class TenantMembershipFilter @Inject constructor(
    private val tenantService: TenantService,
    private val requestContext: RequestContext,
) : ContainerRequestFilter {
    override fun filter(context: ContainerRequestContext) {
        val tenantName = context.uriInfo.pathParameters["tenantName"]?.firstOrNull()
            ?: return

        val tenant = tenantService.findTenantByName(tenantName)
        tenantService.requireTenantMembership(tenant, requestContext.user!!)
        requestContext.tenant = tenant
    }
}
