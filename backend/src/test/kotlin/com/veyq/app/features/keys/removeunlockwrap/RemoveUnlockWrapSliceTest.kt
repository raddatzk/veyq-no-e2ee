package com.veyq.app.features.keys.removeunlockwrap

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@QuarkusTest
class RemoveUnlockWrapSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var keyBlobs: KeyBlobRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var lastOnlyToken: String
    private lateinit var deviceOnlyToken: String

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            seedUser("remove-owner", "remove-owner@example.com", now)
            seedUser("remove-last", "remove-last@example.com", now)
            seedUser("remove-device-only", "remove-device-only@example.com", now)

            seedWrap("remove-owner-password", "remove-owner", "PASSWORD", null, now)
            seedWrap("remove-owner-device-1", "remove-owner", "DEVICE", "device-1", now)
            seedWrap("remove-owner-device-2", "remove-owner", "DEVICE", "device-2", now)
            seedWrap("remove-last-password", "remove-last", "PASSWORD", null, now)
            seedWrap("remove-device-only-device", "remove-device-only", "DEVICE", "device-x", now)
        }
        ownerToken = "Bearer ${sessions.issue("remove-owner")}"
        lastOnlyToken = "Bearer ${sessions.issue("remove-last")}"
        deviceOnlyToken = "Bearer ${sessions.issue("remove-device-only")}"
    }

    @Test
    fun removesOnlySelectedOwnUnlockWraps() {
        given().header("Authorization", ownerToken)
            .queryParam("credentialRef", "device-1")
            .delete("/api/me/key-wraps/DEVICE")
            .then().statusCode(204)

        val deviceRefs = QuarkusTransaction.requiringNew().call {
            keyBlobs.findByUserIdAndUnlockMethod("remove-owner", "DEVICE").mapNotNull { it.credentialRef }.sorted()
        }
        assertEquals(listOf("device-2"), deviceRefs)

        given().header("Authorization", ownerToken)
            .queryParam("credentialRef", "device-missing")
            .delete("/api/me/key-wraps/DEVICE")
            .then().statusCode(404)
            .body("code", `is`("NOT_FOUND"))

        // the mandatory recovery path: PASSWORD cannot be dropped even while other wraps remain
        given().header("Authorization", ownerToken)
            .delete("/api/me/key-wraps/PASSWORD")
            .then().statusCode(409)
            .body("code", `is`("LAST_CAPABLE_METHOD"))

        // and removing the only (password) wrap is likewise the last capable method
        given().header("Authorization", lastOnlyToken)
            .delete("/api/me/key-wraps/PASSWORD")
            .then().statusCode(409)
            .body("code", `is`("LAST_CAPABLE_METHOD"))

        // generic lockout guard still holds for a single non-password wrap
        given().header("Authorization", deviceOnlyToken)
            .queryParam("credentialRef", "device-x")
            .delete("/api/me/key-wraps/DEVICE")
            .then().statusCode(409)
            .body("code", `is`("LAST_UNLOCK_METHOD"))

        given().header("Authorization", "Bearer nope")
            .delete("/api/me/key-wraps/PASSWORD")
            .then().statusCode(401)
    }

    private fun seedUser(id: String, email: String, now: Instant) {
        if (users.findByEmail(email) != null) return
        users.persist(User().apply {
            this.id = id
            this.email = email
            displayName = id
            serverRole = ServerRole.ServerMember
            createdAt = now
        })
    }

    private fun seedWrap(id: String, userId: String, method: String, credentialRef: String?, now: Instant) {
        val existing = keyBlobs.findByUserIdAndUnlockMethod(userId, method).firstOrNull { it.credentialRef == credentialRef }
        if (existing != null) return
        keyBlobs.persist(KeyBlob().apply {
            this.id = id
            this.userId = userId
            unlockMethod = method
            suiteId = "v1"
            wrappedKey = id.toByteArray()
            this.credentialRef = credentialRef
            createdAt = now
        })
    }
}
