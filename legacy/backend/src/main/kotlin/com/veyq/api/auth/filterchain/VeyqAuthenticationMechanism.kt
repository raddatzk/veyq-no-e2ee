package com.veyq.api.auth.filterchain

import com.veyq.api.auth.cookie.CookieConfig
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class VeyqAuthenticationMechanism @Inject constructor(
    private val cookieConfig: CookieConfig,
) : HttpAuthenticationMechanism {
    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager,
    ): Uni<SecurityIdentity?> {
        val path = context.normalizedPath().removePrefix("/")
        val refreshToken = context.request().getCookie(cookieConfig.refreshTokenName)?.value
        val accessToken = context.request().getCookie(cookieConfig.accessTokenName)?.value
            ?: bearerToken(context.request().getHeader("Authorization"))
        val useRefreshToken = path == "api/auth/refresh"

        val requiredToken = if (useRefreshToken) refreshToken else accessToken
        if (requiredToken.isNullOrBlank()) {
            return Uni.createFrom().nullItem()
        }

        val request = AuthenticationRequest(
            TokenCredentials(accessToken, refreshToken),
            useRefreshToken,
        )
        return identityProviderManager.authenticate(request)
    }

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        Uni.createFrom().item(ChallengeData(401))

    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> =
        setOf(AuthenticationRequest::class.java)

    override fun getPriority(): Int = 2_000

    private fun bearerToken(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val prefix = "Bearer "
        if (!header.startsWith(prefix, ignoreCase = true)) return null
        return header.substring(prefix.length).trim().takeIf { it.isNotBlank() }
    }
}
