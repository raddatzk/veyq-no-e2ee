package com.veyq.api.auth.filterchain

import com.veyq.api.domain.UserRepo
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import io.vertx.ext.web.RoutingContext

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION + 100)
class RequestContextFilter @Inject constructor(
    private val identity: SecurityIdentity,
    private val userRepo: UserRepo,
    private val requestContext: RequestContext,
    private val routingContext: RoutingContext,
) : ContainerRequestFilter {
    override fun filter(context: ContainerRequestContext) {
        requestContext.clientIp = clientIp(context)
        if (identity.isAnonymous) {
            return
        }

        val userId = identity.getAttribute<Long>(VeyqSecurityAttributes.USER_ID) ?: return
        requestContext.user = userRepo.findById(userId)
        requestContext.authentication = AuthenticationRequest(
            identity.getCredential(TokenCredentials::class.java),
            context.uriInfo.path == "api/auth/refresh",
        )
    }

    private fun clientIp(context: ContainerRequestContext): String {
        val forwardedFor = context.getHeaderString("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return forwardedFor
            ?: routingContext.request().remoteAddress()?.host()
            ?: "unknown"
    }
}
