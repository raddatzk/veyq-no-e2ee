package com.lunva.api.auth

import com.lunva.api.auth.token.TokenCleanup
import com.lunva.api.domain.AccessToken
import com.lunva.api.domain.AccessTokenRepo
import com.lunva.api.domain.RefreshToken
import com.lunva.api.domain.RefreshTokenRepo
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test

@QuarkusTest
class TokenCleanupTest {
    @Inject
    lateinit var tokenCleanup: TokenCleanup
    @Inject
    lateinit var accessTokenRepo: AccessTokenRepo
    @Inject
    lateinit var refreshTokenRepo: RefreshTokenRepo
    @Inject
    lateinit var userRepo: UserRepo
    @Test
    @TestTransaction
    fun `cleanup deletes old revoked tokens and keeps active ones`() {
        val user = createUser()
        val now = Instant.now()

        val staleRefresh = RefreshToken().also {
            it.tokenHash = UUID.randomUUID().toString().replace("-", "") + "a".repeat(32)
            it.user = user
            it.createdAt = now.minus(120, ChronoUnit.DAYS)
            it.lastAccessedAt = now.minus(120, ChronoUnit.DAYS)
            it.expiresAt = now.minus(120, ChronoUnit.DAYS)
            it.revokedAt = now.minus(120, ChronoUnit.DAYS)
            it.persist()
        }
        val activeRefresh = RefreshToken().also {
            it.tokenHash = UUID.randomUUID().toString().replace("-", "") + "b".repeat(32)
            it.user = user
            it.createdAt = now
            it.lastAccessedAt = now
            it.expiresAt = now.plus(7, ChronoUnit.DAYS)
            it.persist()
        }

        AccessToken().also {
            it.tokenHash = UUID.randomUUID().toString().replace("-", "") + "c".repeat(32)
            it.user = user
            it.refreshToken = staleRefresh
            it.createdAt = now.minus(40, ChronoUnit.DAYS)
            it.lastAccessedAt = now.minus(40, ChronoUnit.DAYS)
            it.expiresAt = now.minus(40, ChronoUnit.DAYS)
            it.revokedAt = now.minus(40, ChronoUnit.DAYS)
            it.persist()
        }
        AccessToken().also {
            it.tokenHash = UUID.randomUUID().toString().replace("-", "") + "d".repeat(32)
            it.user = user
            it.refreshToken = activeRefresh
            it.createdAt = now
            it.lastAccessedAt = now
            it.expiresAt = now.plus(2, ChronoUnit.DAYS)
            it.persist()
        }

        tokenCleanup.cleanup()

        assertThat(refreshTokenRepo.count("user", user))
            .describedAs("Expected only active refresh token to remain")
            .isEqualTo(1)
        assertThat(accessTokenRepo.count("user", user))
            .describedAs("Expected only active access token to remain")
            .isEqualTo(1)
    }

    private fun createUser(): User {
        val unique = UUID.randomUUID().toString().take(8)
        return User().also {
            it.passwordHash = "\$2y\$10\$2bNfOGM2k9b8CKi85z9LqekdJp5EJk4j7n4JrTxf2j2Ff0cRImwR6"
            it.displayName = "Cleanup User $unique"
            it.email = "cleanup-$unique@example.com"
            it.role = ServerRole.ServerOwner
            it.passwordSetAt = Instant.now()
            userRepo.persist(it)
        }
    }
}
