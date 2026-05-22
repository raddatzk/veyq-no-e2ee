package com.veyq.api.auth.filterchain

import com.veyq.api.setup.ServerState
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class VeyqHttpSecurityPolicy(
    private val serverState: ServerState,
) : HttpSecurityPolicy {
    override fun checkPermission(
        routingContext: RoutingContext,
        identity: Uni<SecurityIdentity>,
        requestContext: HttpSecurityPolicy.AuthorizationRequestContext,
    ): Uni<HttpSecurityPolicy.CheckResult> {
        val path = routingContext.normalizedPath()

        if (isPublic(path) || !path.startsWith("/api/")) {
            return HttpSecurityPolicy.CheckResult.permit()
        }

        if (!serverState.setupCompleted) {
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
                path == "/api/auth/passkeys/login/options" ||
                path == "/api/auth/passkeys/login" ||
                path.startsWith("/api/invitations/") ||
                path == "/api/users/password/forgot" ||
                (path.startsWith("/api/users/") && path.endsWith("/password/reset")) ||
                (path.startsWith("/api/users/") && path.endsWith("/invite/accept")) ||
                (path.startsWith("/api/users/") && path.endsWith("/invite/passkey/options")) ||
                (path.startsWith("/api/users/") && path.endsWith("/invite/passkey/accept")) ||
                path == "/api/setup" ||
                (path == "/api/setup/admin" && !serverState.setupCompleted) ||
                (path == "/api/setup/admin/passkey/options" && !serverState.setupCompleted) ||
                (path == "/api/setup/admin/passkey" && !serverState.setupCompleted) ||
                path == "/q/health"
    }
}
