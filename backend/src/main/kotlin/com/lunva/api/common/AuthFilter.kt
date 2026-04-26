package com.lunva.api.common

import com.lunva.api.auth.UnauthorizedException
import com.lunva.api.auth.cookie.CookieConfig
import com.lunva.api.auth.token.TokenService
import com.lunva.api.domain.User
import io.quarkus.security.credential.Credential
import io.quarkus.security.identity.CurrentIdentityAssociation
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
class AuthFilter @Inject constructor(
    private val tokenService: TokenService,
    private val cookieConfig: CookieConfig,
    private val identityAssociation: CurrentIdentityAssociation
) : ContainerRequestFilter {
    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.path.removePrefix("/")
        if (path.startsWith("q/health")
            || path.startsWith("api/auth/login")
        ) {
            return
        }
        else if (!path.startsWith("api/")) {
            return
        }

        val (credentials: TokenCredentials, user: User) = if (path.startsWith("api/auth/refresh")) {
            val refreshToken = requestContext.cookies[cookieConfig.refreshTokenName]?.value
                ?: throw UnauthorizedException("Missing refresh token")

            val user = tokenService.findRefreshToken(refreshToken)?.user
                ?: throw UnauthorizedException("Invalid session")

            val accessToken = requestContext.cookies[cookieConfig.accessTokenName]?.value

            TokenCredentials(accessToken, refreshToken) to user
        } else {
            val accessToken = requestContext.cookies[cookieConfig.accessTokenName]?.value
                ?: throw UnauthorizedException("Missing access token")

            val user = tokenService.findUserByAccessToken(accessToken)
                ?: throw UnauthorizedException("Invalid session")

            val refreshToken = requestContext.cookies[cookieConfig.refreshTokenName]?.value

            TokenCredentials(accessToken, refreshToken) to user
        }

        identityAssociation.identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal(user.username))
            .addCredential(credentials)
            .addRoles(user.roles.map { it.roleName }.toSet())
            .build()
    }

}

data class TokenCredentials(val accessToken: String?, val refreshToken: String?): Credential