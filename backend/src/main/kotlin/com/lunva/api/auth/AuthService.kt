package com.lunva.api.auth

import com.lunva.api.auth.AuthResource.LoginResponse
import com.lunva.api.auth.cookie.CookieService
import com.lunva.api.auth.token.TokenService
import com.lunva.api.common.TokenCredentials
import com.lunva.api.domain.RefreshToken
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.user.UserService
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.NewCookie

@ApplicationScoped
class AuthService @Inject constructor(
    private val userRepo: UserRepo,
    private val passwordService: PasswordService,
    private val securityIdentity: SecurityIdentity,
    private val tokenService: TokenService,
    private val cookieService: CookieService,
    private val userService: UserService,
) {

    data class LoginContext(val response: LoginResponse, val accessCookie: NewCookie, val refreshCookie: NewCookie)
    data class LogoutContext(val accessCookie: NewCookie, val refreshCookie: NewCookie)

    private fun checkLogin(request: AuthResource.LoginRequest): User {
        val username = request.username!!
        val password = request.password!!

        val user = userRepo.findByUsername(username)
            ?: throw UnauthorizedException("Invalid username or password")

        if (!passwordService.matches(password, user.passwordHash)) {
            throw UnauthorizedException("Invalid username or password")
        }
        passwordService.upgradeIfNeeded(user, password)

        return user
    }

    @Transactional
    fun login(request: AuthResource.LoginRequest): LoginContext {
        val user = checkLogin(request)

        return login(user)
    }

    @Transactional
    fun logout(): LogoutContext {
        tokenService.invalidate(securityIdentity.getCredential(TokenCredentials::class.java))

        val refreshCookie = cookieService.deleteRefreshCookie()
        val accessCookie = cookieService.deleteAccessCookie()

        return LogoutContext(accessCookie, refreshCookie)
    }

    @Transactional
    fun refresh(): LoginContext {
        val credential = securityIdentity.getCredential(TokenCredentials::class.java)
        tokenService.invalidate(credential)

        val refreshToken = tokenService.findRefreshToken(credential.refreshToken!!)
            ?: throw UnauthorizedException("Invalid refresh token")

        val user = userService.me()

        return login(user, refreshToken)
    }

    private fun login(user: User, oldRefreshToken: RefreshToken? = null): LoginContext {
        val (accessToken, refreshToken) = tokenService.createToken(user, oldRefreshToken)

        val accessCookie = cookieService.createAccessCookie(accessToken)
        val refreshCookie = cookieService.createRefreshCookie(refreshToken)

        val response = LoginResponse(user.id, user.username, user.displayName)

        return LoginContext(response, accessCookie, refreshCookie)
    }
}