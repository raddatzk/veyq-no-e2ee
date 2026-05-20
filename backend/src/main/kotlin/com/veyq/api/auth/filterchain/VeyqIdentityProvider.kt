package com.veyq.api.auth.filterchain

import com.veyq.api.domain.User
import com.veyq.api.auth.token.TokenService
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

object VeyqSecurityAttributes {
    const val USER_ID = "veyq.userId"
    const val EMAIL = "veyq.email"
    const val ROLE = "veyq.role"
}

class AuthenticationRequest(
    val credentials: TokenCredentials,
    val useRefreshToken: Boolean,
) : BaseAuthenticationRequest()

data class TokenCredentials(val accessToken: String?, val refreshToken: String?) : Credential

@ApplicationScoped
class VeyqIdentityProvider @Inject constructor(
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
                .addAttribute(VeyqSecurityAttributes.USER_ID, user.id)
                .addAttribute(VeyqSecurityAttributes.EMAIL, user.email)
                .addAttribute(VeyqSecurityAttributes.ROLE, user.role)
                .build()
        }
    }

    private fun optionalIdentity(request: AuthenticationRequest): SecurityIdentity? {
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
