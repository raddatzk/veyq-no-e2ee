package com.veyq.api.auth

import com.veyq.api.auth.argon2.Argon2ParameterProvider
import com.veyq.api.auth.argon2.Argon2Parameters
import com.veyq.api.domain.User
import de.mkammerer.argon2.Argon2Factory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.Arrays
import java.util.Base64

@ApplicationScoped
class PasswordService @Inject constructor(
    private val argon2ParameterProvider: Argon2ParameterProvider,
    private val passwordCompromiseChecker: PasswordCompromiseChecker,
) {
    private val random = SecureRandom()
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    @Transactional
    fun setPassword(user: User, rawPassword: String, now: Instant) {
        passwordCompromiseChecker.check(rawPassword)
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
        return try {
            if (isArgon2id(storedHash)) verifyArgon2(rawPassword, storedHash) else false
        } catch (_: Exception) {
            false
        }
    }

    fun hash(rawPassword: String): String {
        val params = argon2ParameterProvider.currentParameters()
        val password = rawPassword.toCharArray()
        return try {
            argon2.hash(params.iterations, params.memoryKiB, params.parallelism, password)
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    fun needsRehash(storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return true
        if (!isArgon2id(storedHash)) return true

        val currentParameters = argon2ParameterProvider.currentParameters()
        val hashParameters = extractArgon2Parameters(storedHash) ?: return true
        return hashParameters.iterations < currentParameters.iterations ||
            hashParameters.memoryKiB < currentParameters.memoryKiB ||
            hashParameters.parallelism < currentParameters.parallelism
    }

    @Transactional
    fun upgradeIfNeeded(user: User, rawPassword: String) {
        if (needsRehash(user.passwordHash)) {
            user.passwordHash = hash(rawPassword)
            user.persist()
        }
    }

    private fun isArgon2id(hash: String): Boolean = hash.startsWith("\$argon2id\$")

    private fun verifyArgon2(rawPassword: String, storedHash: String): Boolean {
        val password = rawPassword.toCharArray()
        return try {
            argon2.verify(storedHash, password)
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private fun extractArgon2Parameters(hash: String): Argon2Parameters? {
        val params = hash.split("$")
            .firstOrNull { it.startsWith("m=") }
            ?: return null
        val values = params.split(",")
            .mapNotNull {
                val keyValue = it.split("=", limit = 2)
                if (keyValue.size == 2) keyValue[0] to keyValue[1].toIntOrNull() else null
            }
            .toMap()

        return Argon2Parameters(
            iterations = values["t"] ?: return null,
            memoryKiB = values["m"] ?: return null,
            parallelism = values["p"] ?: return null,
        )
    }
}
