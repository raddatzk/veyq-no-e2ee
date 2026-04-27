package com.lunva.api.auth

import com.lunva.api.auth.token.TokenService
import com.lunva.api.domain.AccessTokenRepo
import com.lunva.api.domain.RefreshTokenRepo
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertNotNull(firstRefreshEntity)

        val (_, secondRefreshToken) = tokenService.createToken(user, firstRefreshEntity)
        val secondRefreshEntity = tokenService.findRefreshToken(secondRefreshToken)
        assertNotNull(secondRefreshEntity)

        assertEquals(secondRefreshEntity.id, firstRefreshEntity.rotatedTo?.id)
    }

    @Test
    @TestTransaction
    fun `invalidateRefreshToken revokes given refresh token and its related access token`() {
        val user = createUser()

        val (firstAccessToken, firstRefreshToken) = tokenService.createToken(user, null)
        val firstRefreshEntity = tokenService.findRefreshToken(firstRefreshToken)
        assertNotNull(firstRefreshEntity)

        tokenService.createToken(user, firstRefreshEntity)
        assertNotNull(tokenService.findUserByRefreshToken(firstRefreshToken))
        assertNotNull(tokenService.findUserByAccessToken(firstAccessToken))

        tokenService.invalidateRefreshToken(firstRefreshToken)
        refreshTokenRepo.flush()
        accessTokenRepo.flush()
        entityManager.clear()

        assertNull(tokenService.findUserByRefreshToken(firstRefreshToken), "Expected refresh token to be inactive after revoke")
        assertNull(tokenService.findUserByAccessToken(firstAccessToken), "Expected access token to be inactive after refresh-token revoke")
    }

    @Test
    @TestTransaction
    fun `findUserByAccessToken returns null after access token is revoked`() {
        val user = createUser()
        val (accessToken, _) = tokenService.createToken(user, null)

        val beforeRevoke = tokenService.findUserByAccessToken(accessToken)
        assertNotNull(beforeRevoke)
        assertEquals(user.id, beforeRevoke.id)

        tokenService.invalidateAccessToken(accessToken, Instant.now())
        val afterRevoke = tokenService.findUserByAccessToken(accessToken)
        assertNull(afterRevoke)
    }

    private fun createUser(): User {
        val unique = UUID.randomUUID().toString().take(8)
        return User().also {
            it.passwordHash = "\$2y\$10\$2bNfOGM2k9b8CKi85z9LqekdJp5EJk4j7n4JrTxf2j2Ff0cRImwR6"
            it.displayName = "Token User $unique"
            it.email = "token-$unique@example.com"
            it.role = ServerRole.SERVER_OWNER
            userRepo.persist(it)
        }
    }

}
