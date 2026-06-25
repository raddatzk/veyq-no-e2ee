package com.veyq.app.features.keys.upsertunlockwrap

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@QuarkusTest
class UnlockWrapSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var keyBlobs: KeyBlobRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private val replacement = "cmVwbGFjZWQ" // base64url("replaced")

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("wrap-owner@example.com") == null) {
                users.persist(User().apply {
                    id = "wrap-owner"; email = "wrap-owner@example.com"; displayName = "Wrap Owner"
                    serverRole = ServerRole.ServerMember; createdAt = Instant.now()
                })
            }
            if (keyBlobs.findByUserIdAndUnlockMethod("wrap-owner", "PASSWORD").none { it.credentialRef == null }) {
                keyBlobs.persist(KeyBlob().apply {
                    id = "wrap-password"; userId = "wrap-owner"; unlockMethod = "PASSWORD"; suiteId = "v1"
                    wrappedKey = "old".toByteArray(); kdfParams = "salt".toByteArray(); createdAt = Instant.now()
                })
            }
        }
        ownerToken = "Bearer ${sessions.issue("wrap-owner")}"
    }

    @Test
    fun upsertsOnlyTheAuthenticatedUsersUnlockWrap() {
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(
                """
                {"masterKeyWrap":{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"$replacement","kdfParams":"bmV3LXNhbHQ"}}
                """.trimIndent(),
            )
            .put("/api/me/key-wraps/PASSWORD")
            .then().statusCode(200)
            .body("unlockMethod", `is`("PASSWORD"))
            .body("suiteId", `is`("v1"))
            .body("wrappedKey", `is`(replacement))
            .body("kdfParams", `is`("bmV3LXNhbHQ"))

        val stored = QuarkusTransaction.requiringNew().call {
            keyBlobs.findByUserIdAndUnlockMethod("wrap-owner", "PASSWORD").single { it.credentialRef == null }
        }
        assertContentEquals(decode(replacement), stored.wrappedKey)
        assertContentEquals(decode("bmV3LXNhbHQ"), stored.kdfParams)

        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body(
                """
                {"masterKeyWrap":{"unlockMethod":"DEVICE","suiteId":"v1","wrappedKey":"ZGV2aWNl","credentialRef":"device-1"}}
                """.trimIndent(),
            )
            .put("/api/me/key-wraps/DEVICE")
            .then().statusCode(200)
            .body("unlockMethod", `is`("DEVICE"))
            .body("credentialRef", `is`("device-1"))
        val deviceCount = QuarkusTransaction.requiringNew().call {
            keyBlobs.findByUserIdAndUnlockMethod("wrap-owner", "DEVICE").count { it.credentialRef == "device-1" }
        }
        assertEquals(1, deviceCount)

        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"masterKeyWrap":{"unlockMethod":"DEVICE","suiteId":"v1","wrappedKey":"ZGV2aWNl"}}""")
            .put("/api/me/key-wraps/PASSWORD")
            .then().statusCode(400)
            .body("code", `is`("UNLOCK_METHOD_MISMATCH"))

        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"masterKeyWrap":{"unlockMethod":"PASSWORD","suiteId":"v2","wrappedKey":"$replacement"}}""")
            .put("/api/me/key-wraps/PASSWORD")
            .then().statusCode(400)
            .body("code", `is`("UNKNOWN_SUITE"))

        given().header("Authorization", "Bearer nope").contentType(ContentType.JSON)
            .body("""{"masterKeyWrap":{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"$replacement"}}""")
            .put("/api/me/key-wraps/PASSWORD")
            .then().statusCode(401)
    }

    private fun decode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s.padEnd(s.length + (4 - s.length % 4) % 4, '='))
}
