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

    private fun seed(token: String, expiresAt: Instant) {
        QuarkusTransaction.requiringNew().run {
            sessions.persist(
                Session().apply {
                    id = UUID.randomUUID().toString(); this.token = token; userId = "gc-user"
                    createdAt = Instant.now().minus(40, ChronoUnit.DAYS); this.expiresAt = expiresAt
                },
            )
        }
    }

    @Test
    fun purgeExpiredDropsOnlyExpiredRows() {
        val now = Instant.now()
        seed("gc-expired", now.minus(1, ChronoUnit.DAYS))
        seed("gc-valid", now.plus(1, ChronoUnit.DAYS))

        val purged = service.purgeExpired()

        assertEquals(1, purged, "exactly the expired session is swept")
        QuarkusTransaction.requiringNew().run {
            assertNull(sessions.findByToken("gc-expired"), "expired row is gone")
            assertNotNull(sessions.findByToken("gc-valid"), "valid row survives")
        }
        // a swept token never resolves
        assertNull(service.resolve("Bearer gc-expired"))
    }
}
