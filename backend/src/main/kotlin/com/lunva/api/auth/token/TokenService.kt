package com.lunva.api.auth.token

import com.lunva.api.auth.UnauthorizedException
import com.lunva.api.auth.filterchain.TokenCredentials
import com.lunva.api.domain.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

@ApplicationScoped
class TokenService @Inject constructor(
    private val tokenConfig: TokenConfig,
    private val accessTokenRepo: AccessTokenRepo,
    private val refreshTokenRepo: RefreshTokenRepo
) {
    private val random = SecureRandom()

    @Inject
    lateinit var self: jakarta.enterprise.inject.Instance<TokenService>

    fun createToken(user: User, oldRefreshToken: RefreshToken?): Pair<String, String> {
        val accessToken = newSessionId()
        val refreshToken = newSessionId()
        val now = Instant.now()

        val refreshTokenEntity = RefreshToken().also {
            it.tokenHash = hash(refreshToken)
            it.user = user
            it.createdAt = now
            it.lastAccessedAt = now
            it.expiresAt = now.plus(Duration.ofMinutes(tokenConfig.refreshTokenTtlMinutes))
            it.persist()
        }
        AccessToken().also {
            it.tokenHash = hash(accessToken)
            it.user = user
            it.refreshToken = refreshTokenEntity
            it.createdAt = now
            it.lastAccessedAt = now
            it.expiresAt = now.plus(Duration.ofMinutes(tokenConfig.accessTokenTtlMinutes))
            it.persist()
        }

        oldRefreshToken?.rotatedTo = refreshTokenEntity

        return accessToken to refreshToken
    }

    fun invalidate(token: TokenCredentials) {
        val revokedAt = Instant.now()
        invalidateAccessToken(token.accessToken, revokedAt)

        val refreshToken = token.refreshToken?.let { findRefreshToken(it) }
            ?: throw UnauthorizedException("Invalid refresh token")
        if (refreshToken.revokedAt != null) {
            self.get().invalidateRefreshTokenRecursive(refreshToken.tokenHash, revokedAt)
            throw UnauthorizedException("Invalid session")
        }

        invalidateRefreshToken(token.refreshToken, revokedAt)
    }

    fun invalidateAccessToken(token: String?, revokedAt: Instant = Instant.now()) {
        if (token != null) {
            accessTokenRepo.findByTokenHash(hash(token))?.also { token ->
                token.revokedAt = revokedAt
            }
        }
    }

    fun invalidateRefreshToken(token: String?, revokedAt: Instant = Instant.now()) {
        if (token != null) {
            invalidateRefreshTokenRecursiveInternal(hash(token), revokedAt)
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun invalidateRefreshTokenRecursive(token: String?, revokedAt: Instant = Instant.now()) {
        invalidateRefreshTokenRecursiveInternal(token, revokedAt)
    }

    private fun invalidateRefreshTokenRecursiveInternal(token: String?, revokedAt: Instant = Instant.now()) {
        if (token != null) {
            val refreshToken = refreshTokenRepo.findByTokenHash(token)?.also { reToken ->
                reToken.revokedAt = revokedAt
            }

            refreshToken?.id?.let { refreshTokenId ->
                accessTokenRepo.list("refreshToken.id", refreshTokenId).forEach { acToken ->
                    acToken.revokedAt = revokedAt
                }
            }
            invalidateRefreshTokenRecursiveInternal(refreshToken?.rotatedTo?.tokenHash, revokedAt)
        }
    }

    @Transactional
    fun findUserByAccessToken(accessToken: String): User? {
        return accessTokenRepo.findActiveByTokenHash(hash(accessToken))?.user
    }

    @Transactional
    fun findUserByRefreshToken(refreshToken: String): User? {
        return refreshTokenRepo.findActiveByTokenHash(hash(refreshToken))?.user
    }

    @Transactional
    fun findRefreshToken(refreshToken: String): RefreshToken? {
        return refreshTokenRepo.findByTokenHash(hash(refreshToken))
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
