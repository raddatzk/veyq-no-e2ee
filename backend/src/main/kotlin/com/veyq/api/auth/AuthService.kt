package com.veyq.api.auth

import com.veyq.api.auth.cookie.CookieService
import com.veyq.api.auth.token.TokenService
import com.veyq.api.domain.RefreshToken
import com.veyq.api.domain.User
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.UserRepo
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.ServerRole
import com.veyq.api.generated.model.LoginRequest
import com.veyq.api.generated.model.UserSummary
import com.veyq.api.security.RateLimitService
import com.veyq.api.generated.model.ServerRole as ApiServerRole
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.NewCookie

data class LoginContext(val response: UserSummary, val accessCookie: NewCookie, val refreshCookie: NewCookie)
data class LogoutContext(val accessCookie: NewCookie, val refreshCookie: NewCookie)

@ApplicationScoped
class AuthService @Inject constructor(
    private val userRepo: UserRepo,
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
    private val passwordService: PasswordService,
    private val tokenService: TokenService,
    private val cookieService: CookieService,
    private val requestContext: RequestContext,
    private val rateLimitService: RateLimitService,
) {

    private fun checkLogin(request: LoginRequest): User {
        val email = request.email.trim().lowercase()
        val password = request.password

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
        val email = request.email.trim().lowercase()
        rateLimitService.checkLoginAttempt(email)
        val user = try {
            checkLogin(request)
        } catch (exception: UnauthorizedException) {
            rateLimitService.recordLoginFailure(email)
            throw exception
        }
        rateLimitService.recordLoginSuccess(email)

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

        val response = UserSummary(
            user.id,
            user.email,
            user.displayName,
            user.permalink,
            user.role.toAuthApiRole(),
            passkeyCredentialRepo.countByUser(user).toInt(),
        )

        return LoginContext(response, accessCookie, refreshCookie)
    }

    private fun ServerRole.toAuthApiRole(): ApiServerRole =
        when (this) {
            ServerRole.ServerOwner -> ApiServerRole.ServerOwner
            ServerRole.ServerMember -> ApiServerRole.ServerMember
        }
}
