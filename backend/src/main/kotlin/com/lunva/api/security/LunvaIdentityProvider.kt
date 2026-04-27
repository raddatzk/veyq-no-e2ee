package com.lunva.api.security

import com.lunva.api.domain.User
import com.lunva.api.auth.token.TokenService
import io.quarkus.security.credential.Credential
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.IdentityProvider
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.BaseAuthenticationRequest
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

object LunvaSecurityAttributes {
    const val USER_ID = "lunva.userId"
    const val EMAIL = "lunva.email"
    const val ROLE = "lunva.role"
}

class AuthenticationRequest(
    val credentials: TokenCredentials,
    val useRefreshToken: Boolean,
    val optional: Boolean = false,
) : BaseAuthenticationRequest()

data class TokenCredentials(val accessToken: String?, val refreshToken: String?) : Credential

@ApplicationScoped
class LunvaIdentityProvider @Inject constructor(
    private val tokenService: TokenService,
) : IdentityProvider<AuthenticationRequest> {
    override fun getRequestType(): Class<AuthenticationRequest> =
        AuthenticationRequest::class.java

    override fun authenticate(
        request: AuthenticationRequest,
        context: AuthenticationRequestContext,
    ): Uni<SecurityIdentity?> {
        return context.runBlocking {
            val user = findUser(request) ?: return@runBlocking optionalIdentity(request)

            QuarkusSecurityIdentity.builder()
                .setPrincipal(QuarkusPrincipal(user.email))
                .addCredential(request.credentials)
                .addRole(user.role.toString())
                .addAttribute(LunvaSecurityAttributes.USER_ID, user.id)
                .addAttribute(LunvaSecurityAttributes.EMAIL, user.email)
                .addAttribute(LunvaSecurityAttributes.ROLE, user.role)
                .build()
        }
    }

    private fun optionalIdentity(request: AuthenticationRequest): SecurityIdentity? {
        if (!request.optional) {
            return null
        }
        return QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal("anonymous"))
            .setAnonymous(true)
            .build()
    }

    private fun findUser(request: AuthenticationRequest): User? {
        return if (request.useRefreshToken) {
            request.credentials.refreshToken?.let { tokenService.findUserByRefreshToken(it) }
        } else {
            request.credentials.accessToken?.let { tokenService.findUserByAccessToken(it) }
        }
    }
}
