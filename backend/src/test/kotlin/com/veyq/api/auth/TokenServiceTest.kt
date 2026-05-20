package com.veyq.api.auth

import com.veyq.api.auth.token.TokenService
import com.veyq.api.domain.AccessTokenRepo
import com.veyq.api.domain.RefreshTokenRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

@QuarkusTest
class TokenServiceTest {
    @Inject
    lateinit var tokenService: TokenService
    @Inject
    lateinit var accessTokenRepo: AccessTokenRepo
    @Inject
    lateinit var refreshTokenRepo: RefreshTokenRepo
    @Inject
    lateinit var userRepo: UserRepo
    @Inject
    lateinit var entityManager: EntityManager

    @Test
    @TestTransaction
    fun `createToken rotates refresh token by linking successor`() {
        val user = createUser()
        val (_, firstRefreshToken) = tokenService.createToken(user, null)
        val firstRefreshEntity = tokenService.findRefreshToken(firstRefreshToken)
        assertThat(firstRefreshEntity).isNotNull

        val (_, secondRefreshToken) = tokenService.createToken(user, firstRefreshEntity)
        val secondRefreshEntity = tokenService.findRefreshToken(secondRefreshToken)
        assertThat(secondRefreshEntity).isNotNull

        assertThat(firstRefreshEntity!!.rotatedTo?.id).isEqualTo(secondRefreshEntity!!.id)
    }

    @Test
    @TestTransaction
    fun `invalidateRefreshToken revokes given refresh token and its related access token`() {
        val user = createUser()

        val (firstAccessToken, firstRefreshToken) = tokenService.createToken(user, null)
        val firstRefreshEntity = tokenService.findRefreshToken(firstRefreshToken)
        assertThat(firstRefreshEntity).isNotNull

        tokenService.createToken(user, firstRefreshEntity)
        assertThat(tokenService.findUserByRefreshToken(firstRefreshToken)).isNotNull
        assertThat(tokenService.findUserByAccessToken(firstAccessToken)).isNotNull

        tokenService.invalidateRefreshToken(firstRefreshToken)
        refreshTokenRepo.flush()
        accessTokenRepo.flush()
        entityManager.clear()

        assertThat(tokenService.findUserByRefreshToken(firstRefreshToken))
            .describedAs("Expected refresh token to be inactive after revoke")
            .isNull()
        assertThat(tokenService.findUserByAccessToken(firstAccessToken))
            .describedAs("Expected access token to be inactive after refresh-token revoke")
            .isNull()
    }

    @Test
    @TestTransaction
    fun `findUserByAccessToken returns null after access token is revoked`() {
        val user = createUser()
        val (accessToken, _) = tokenService.createToken(user, null)

        val beforeRevoke = tokenService.findUserByAccessToken(accessToken)
        assertThat(beforeRevoke).isNotNull
        assertThat(beforeRevoke!!.id).isEqualTo(user.id)

        tokenService.invalidateAccessToken(accessToken, Instant.now())
        val afterRevoke = tokenService.findUserByAccessToken(accessToken)
        assertThat(afterRevoke).isNull()
    }

    private fun createUser(): User {
        val unique = UUID.randomUUID().toString().take(8)
        return User().also {
            it.passwordHash = "\$2y\$10\$2bNfOGM2k9b8CKi85z9LqekdJp5EJk4j7n4JrTxf2j2Ff0cRImwR6"
            it.displayName = "Token User $unique"
            it.email = "token-$unique@example.com"
            it.role = ServerRole.ServerOwner
            it.passwordSetAt = Instant.now()
            userRepo.persist(it)
        }
    }

}
