package com.lunva.api.auth

import com.lunva.api.auth.cookie.CookieService
import com.lunva.api.auth.token.TokenService
import com.lunva.api.domain.RefreshToken
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.generated.model.LoginRequest
import com.lunva.api.generated.model.LoginSessionResponse
import com.lunva.api.generated.model.ServerRole as ApiServerRole
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.NewCookie

data class LoginContext(val response: LoginSessionResponse, val accessCookie: NewCookie, val refreshCookie: NewCookie)
data class LogoutContext(val accessCookie: NewCookie, val refreshCookie: NewCookie)

@ApplicationScoped
class AuthService @Inject constructor(
    private val userRepo: UserRepo,
    private val passwordService: PasswordService,
    private val tokenService: TokenService,
    private val cookieService: CookieService,
    private val requestContext: RequestContext,
) {

    private fun checkLogin(request: LoginRequest): User {
        val email = request.email!!.trim().lowercase()
        val password = request.password!!

        val user = userRepo.findByEmail(email)
            ?: throw UnauthorizedException("Invalid email or password")

        if (user.passwordSetAt == null) {
            throw UnauthorizedException("Invalid email or password")
        }

        if (!passwordService.matches(password, user.passwordHash)) {
            throw UnauthorizedException("Invalid email or password")
        }
        passwordService.upgradeIfNeeded(user, password)

        return user
    }

    @Transactional
    fun login(request: LoginRequest): LoginContext {
        val user = checkLogin(request)

        return login(user)
    }

    @Transactional
    fun logout(): LogoutContext {
        tokenService.invalidate(requestContext.authentication!!.credentials)

        val refreshCookie = cookieService.deleteRefreshCookie()
        val accessCookie = cookieService.deleteAccessCookie()

        return LogoutContext(accessCookie, refreshCookie)
    }

    @Transactional
    fun refresh(): LoginContext {
        val credential = requestContext.authentication!!.credentials
        tokenService.invalidate(credential)

        val refreshToken = tokenService.findRefreshToken(credential.refreshToken!!)
            ?: throw UnauthorizedException("Invalid refresh token")

        val user = requestContext.user!!

        return login(user, refreshToken)
    }

    @Transactional
    fun loginUser(user: User): LoginContext =
        login(user)

    private fun login(user: User, oldRefreshToken: RefreshToken? = null): LoginContext {
        val (accessToken, refreshToken) = tokenService.createToken(user, oldRefreshToken)

        val accessCookie = cookieService.createAccessCookie(accessToken)
        val refreshCookie = cookieService.createRefreshCookie(refreshToken)

        val response = LoginSessionResponse(user.id, user.email, user.displayName, user.role.toAuthApiRole())

        return LoginContext(response, accessCookie, refreshCookie)
    }

    private fun com.lunva.api.domain.ServerRole.toAuthApiRole(): ApiServerRole =
        when (this) {
            com.lunva.api.domain.ServerRole.ServerOwner -> ApiServerRole.ServerOwner
            com.lunva.api.domain.ServerRole.ServerMember -> ApiServerRole.ServerMember
        }
}
