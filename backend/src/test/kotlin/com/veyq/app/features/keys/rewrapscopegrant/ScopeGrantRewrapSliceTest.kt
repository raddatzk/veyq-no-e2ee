package com.veyq.app.features.keys.rewrapscopegrant

import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
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

@QuarkusTest
class ScopeGrantRewrapSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var sessions: SessionService
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository

    private lateinit var ownerToken: String
    private lateinit var strangerToken: String

    private val newGrant = "bmV3LWdyYW50" // base64url("new-grant")

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("rewrap-owner@example.com") == null) {
                users.persist(User().apply {
                    id = "rewrap-owner"; email = "rewrap-owner@example.com"; displayName = "Rewrap Owner"
                    serverRole = ServerRole.ServerMember; createdAt = now
                })
            }
            if (users.findByEmail("rewrap-stranger@example.com") == null) {
                users.persist(User().apply {
                    id = "rewrap-stranger"; email = "rewrap-stranger@example.com"; displayName = "Rewrap Stranger"
                    serverRole = ServerRole.ServerMember; createdAt = now
                })
            }
            if (scopes.findById("rewrap-scope") == null) {
                scopes.persist(Scope().apply {
                    id = "rewrap-scope"; ownerUserId = "rewrap-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"
                    kemPublicKey = "scope-public".toByteArray(); createdAt = now
                })
            }
            if (grants.find("rewrap-scope", "rewrap-owner") == null) {
                grants.persist(ScopeGrant().apply {
                    id = "rewrap-grant"; scopeId = "rewrap-scope"; userId = "rewrap-owner"; suiteId = "v1"
                    wrappedPrivateKey = "old-grant".toByteArray(); createdAt = now
                })
            }
        }
        ownerToken = "Bearer ${sessions.issue("rewrap-owner")}"
        strangerToken = "Bearer ${sessions.issue("rewrap-stranger")}"
    }

    @Test
    fun rewrapsOnlyAnExistingOwnScopeGrant() {
        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$newGrant"}}""")
            .put("/api/scopes/rewrap-scope/grants/me")
            .then().statusCode(200)
            .body("scopeId", `is`("rewrap-scope"))
            .body("scopePublicKey.kemPublicKey", `is`("c2NvcGUtcHVibGlj"))
            .body("wrappedScopePrivateKey.bytes", `is`(newGrant))

        val stored = QuarkusTransaction.requiringNew().call {
            grants.find("rewrap-scope", "rewrap-owner")?.wrappedPrivateKey ?: error("missing grant")
        }
        assertContentEquals(decode(newGrant), stored)

        given().header("Authorization", strangerToken).contentType(ContentType.JSON)
            .body("""{"wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$newGrant"}}""")
            .put("/api/scopes/rewrap-scope/grants/me")
            .then().statusCode(403)
            .body("code", `is`("NOT_A_KEY_HOLDER"))

        given().header("Authorization", ownerToken).contentType(ContentType.JSON)
            .body("""{"wrappedScopePrivateKey":{"suiteId":"v2","bytes":"$newGrant"}}""")
            .put("/api/scopes/rewrap-scope/grants/me")
            .then().statusCode(400)
            .body("code", `is`("UNKNOWN_SUITE"))

        given().header("Authorization", "Bearer nope").contentType(ContentType.JSON)
            .body("""{"wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$newGrant"}}""")
            .put("/api/scopes/rewrap-scope/grants/me")
            .then().statusCode(401)
    }

    private fun decode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s.padEnd(s.length + (4 - s.length % 4) % 4, '='))
}
