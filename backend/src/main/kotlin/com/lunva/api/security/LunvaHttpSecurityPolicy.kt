package com.lunva.api.security

import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class LunvaHttpSecurityPolicy : HttpSecurityPolicy {
    override fun checkPermission(
        routingContext: RoutingContext,
        identity: Uni<SecurityIdentity>,
        requestContext: HttpSecurityPolicy.AuthorizationRequestContext,
    ): Uni<HttpSecurityPolicy.CheckResult> {
        val path = routingContext.normalizedPath()
        if (isPublic(path) || !path.startsWith("/api/")) {
            return HttpSecurityPolicy.CheckResult.permit()
        }

        return identity.map {
            if (it.isAnonymous) {
                HttpSecurityPolicy.CheckResult.DENY
            } else {
                HttpSecurityPolicy.CheckResult.PERMIT
            }
        }
    }

    private fun isPublic(path: String): Boolean {
        return path == "/api/auth/login" ||
            path == "/api/setup" ||
            path == "/api/setup/admin" ||
            path == "/q/health"
    }
}
