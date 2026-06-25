package com.veyq.app.shared.security

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@QuarkusTest
class SessionGcTest {

    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var service: SessionService
    @Inject lateinit var users: UserRepository

    @BeforeTest
    fun seedUser() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("gc@example.com") == null) {
                users.persist(
                    User().apply {
                        id = "gc-user"; email = "gc@example.com"; displayName = "GC"
                        serverRole = ServerRole.ServerMember; systemAccount = true; createdAt = Instant.now()
                    },
                )
            }
        }
    }

    private fun seed(token: String, expiresAt: Instant, revokedAt: Instant? = null) {
        QuarkusTransaction.requiringNew().run {
            sessions.persist(
                Session().apply {
                    id = UUID.randomUUID().toString(); this.tokenHash = token; userId = "gc-user"
                    rootSessionId = id
                    createdAt = Instant.now().minus(40, ChronoUnit.DAYS); this.expiresAt = expiresAt
                    this.revokedAt = revokedAt
                },
            )
        }
    }

    @Test
    fun purgeExpiredDropsOnlyRowsPastAuditRetention() {
        val now = Instant.now()
        seed("gc-expired-old", now.minus(31, ChronoUnit.DAYS))
        seed("gc-expired-recent", now.minus(1, ChronoUnit.DAYS))
        seed("gc-revoked-old", now.plus(1, ChronoUnit.DAYS), now.minus(31, ChronoUnit.DAYS))
        seed("gc-revoked-recent", now.plus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))
        seed("gc-valid", now.plus(1, ChronoUnit.DAYS))

        val purged = service.purgeExpired()

        assertEquals(2, purged, "only expired/revoked rows past retention are swept")
        QuarkusTransaction.requiringNew().run {
            assertNull(sessions.findByTokenHash("gc-expired-old"), "old expired row is gone")
            assertNotNull(sessions.findByTokenHash("gc-expired-recent"), "recent expired row survives audit retention")
            assertNull(sessions.findByTokenHash("gc-revoked-old"), "old revoked row is gone")
            assertNotNull(sessions.findByTokenHash("gc-revoked-recent"), "recent revoked row survives audit retention")
            assertNotNull(sessions.findByTokenHash("gc-valid"), "valid row survives")
        }
        // a swept token never resolves
        assertNull(service.resolve("Bearer gc-expired-old"))
    }
}
