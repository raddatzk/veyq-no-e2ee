package com.veyq.api.auth

import com.veyq.api.auth.bcrypt.BcryptCostProvider
import com.veyq.api.domain.User
import io.quarkus.elytron.security.common.BcryptUtil
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@ApplicationScoped
class PasswordService @Inject constructor(
    private val bcryptCostProvider: BcryptCostProvider
) {
    private val random = SecureRandom()

    @Transactional
    fun setPassword(user: User, rawPassword: String, now: Instant) {
        user.apply {
            passwordHash = hash(rawPassword)
            passwordSetAt = now
        }
    }

    @Transactional
    fun updatePassword(user: User, currentPassword: String, newPassword: String, now: Instant = Instant.now()) {
        if (!matches(currentPassword, user.passwordHash)) {
            throw UnauthorizedException("Invalid current password")
        }
        setPassword(user, newPassword, now)
    }

    fun randomPasswordHash(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return hash(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }

    fun matches(rawPassword: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false
        if (!isBcrypt(storedHash)) return false
        return try {
            BcryptUtil.matches(rawPassword, storedHash)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun hash(rawPassword: String): String {
        val cost = bcryptCostProvider.currentCost()
        return BcryptUtil.bcryptHash(rawPassword, cost)
    }

    fun needsRehash(storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return true
        if (!isBcrypt(storedHash)) return true

        val currentCost = bcryptCostProvider.currentCost()
        val hashCost = extractBcryptCost(storedHash) ?: return true
        return hashCost < currentCost
    }

    @Transactional
    fun upgradeIfNeeded(user: User, rawPassword: String) {
        if (needsRehash(user.passwordHash)) {
            user.passwordHash = hash(rawPassword)
            user.persist()
        }
    }

    private fun isBcrypt(hash: String): Boolean = hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$")

    private fun extractBcryptCost(hash: String): Int? {
        val parts = hash.split("$")
        if (parts.size < 3) return null
        return parts[2].toIntOrNull()
    }
}
