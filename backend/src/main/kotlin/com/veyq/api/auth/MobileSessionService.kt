package com.veyq.api.auth

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.auth.token.TokenService
import com.veyq.api.domain.MobileSessionCode
import com.veyq.api.domain.MobileSessionCodeRepo
import com.veyq.api.generated.model.MobileSessionCodeRequest
import com.veyq.api.generated.model.MobileSessionCodeResponse
import com.veyq.api.generated.model.MobileSessionExchangeRequest
import com.veyq.api.generated.model.MobileSessionRefreshRequest
import com.veyq.api.generated.model.NativeMobileSessionResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

@ApplicationScoped
class MobileSessionService(
    private val requestContext: RequestContext,
    private val codeRepo: MobileSessionCodeRepo,
    private val tokenService: TokenService,
    private val authService: AuthService,
) {
    private val random = SecureRandom()

    @Transactional
    fun createCode(request: MobileSessionCodeRequest): MobileSessionCodeResponse {
        val user = requestContext.user ?: throw UnauthorizedException("Authentication required")
        val baseRedirectUri = validateRedirectUri(request.redirectUri)
        val state = validateState(request.state)
        val rawCode = newToken()
        val now = Instant.now()

        MobileSessionCode().also {
            it.codeHash = hash(rawCode)
            it.user = user
            it.redirectUri = baseRedirectUri.toString()
            it.state = state
            it.createdAt = now
            it.expiresAt = now.plus(CODE_TTL)
            it.persist()
        }

        return MobileSessionCodeResponse(
            code = rawCode,
            redirectUri = callbackUri(baseRedirectUri, rawCode, state),
            state = state,
        )
    }

    @Transactional
    fun exchange(request: MobileSessionExchangeRequest): NativeMobileSessionResponse {
        val code = codeRepo.findByCodeHashForUpdate(hash(request.code))
            ?: throw BadRequestException("Invalid mobile session code", "MOBILE_SESSION_CODE_INVALID")
        val now = Instant.now()
        if (code.consumedAt != null || !code.expiresAt.isAfter(now)) {
            throw BadRequestException("Invalid mobile session code", "MOBILE_SESSION_CODE_INVALID")
        }
        code.consumedAt = now

        val tokens = tokenService.createToken(code.user, oldRefreshToken = null)
        return NativeMobileSessionResponse(
            tokenType = NativeMobileSessionResponse.TokenType.Bearer,
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt.atOffset(ZoneOffset.UTC),
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt.atOffset(ZoneOffset.UTC),
            user = authService.userSummary(code.user),
        )
    }

    @Transactional
    fun refresh(request: MobileSessionRefreshRequest): NativeMobileSessionResponse {
        val refreshToken = tokenService.findRefreshToken(request.refreshToken)
            ?: throw UnauthorizedException("Invalid refresh token")
        val now = Instant.now()
        if (refreshToken.revokedAt != null || !refreshToken.expiresAt.isAfter(now)) {
            tokenService.invalidateRefreshToken(request.refreshToken, now)
            throw UnauthorizedException("Invalid refresh token")
        }

        tokenService.invalidateRefreshToken(request.refreshToken, now)
        val tokens = tokenService.createToken(refreshToken.user, oldRefreshToken = refreshToken)
        return NativeMobileSessionResponse(
            tokenType = NativeMobileSessionResponse.TokenType.Bearer,
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt.atOffset(ZoneOffset.UTC),
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt.atOffset(ZoneOffset.UTC),
            user = authService.userSummary(refreshToken.user),
        )
    }

    @Transactional
    fun logout() {
        val accessToken = requestContext.authentication?.credentials?.accessToken
        tokenService.invalidateSessionByAccessToken(accessToken)
    }

    private fun validateRedirectUri(uri: URI): URI {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "veyq" || uri.host != "auth" || uri.path != "/callback") {
            throw BadRequestException("Invalid mobile redirectUri", "MOBILE_REDIRECT_URI_INVALID")
        }
        if (uri.query != null || uri.fragment != null || uri.toString().length > 1024) {
            throw BadRequestException("Invalid mobile redirectUri", "MOBILE_REDIRECT_URI_INVALID")
        }
        return uri
    }

    private fun validateState(state: String?): String? {
        val trimmed = state?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.length > 512) throw BadRequestException("state is too long", "MOBILE_STATE_TOO_LONG")
        return trimmed
    }

    private fun callbackUri(base: URI, code: String, state: String?): URI {
        val query = buildString {
            append("code=")
            append(encodeQueryValue(code))
            if (state != null) {
                append("&state=")
                append(encodeQueryValue(state))
            }
        }
        return URI(base.scheme, base.authority, base.path, query, null)
    }

    private fun encodeQueryValue(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val CODE_TTL: Duration = Duration.ofMinutes(5)
    }
}
