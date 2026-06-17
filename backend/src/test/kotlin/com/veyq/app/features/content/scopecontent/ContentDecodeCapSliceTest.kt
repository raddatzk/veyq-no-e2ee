package com.veyq.app.features.content.scopecontent

import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeKind
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * uc.content (decode cap) — the server bounds a single sealed-content payload (node/item/journal/track) BEFORE
 * decoding it, so an over-large base64 string can't trigger an OOM allocation. A tiny limit is injected so the
 * bound is cheap to hit.
 */
@QuarkusTest
@TestProfile(ContentDecodeCapSliceTest.TinyContent::class)
class ContentDecodeCapSliceTest {

    class TinyContent : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("veyq.content.max-sealed-bytes" to "8")
    }

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var token: String
    private val scopeId = "cap-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("cap-owner@example.com") == null) {
                users.persist(User().apply { id = "cap-owner"; email = "cap-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
                scopes.persist(Scope().apply { id = scopeId; ownerUserId = "cap-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "cap-grant"; this.scopeId = this@ContentDecodeCapSliceTest.scopeId; userId = "cap-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                contentKeys.persist(ScopeContentKey().apply { id = "cap-ck"; this.scopeId = this@ContentDecodeCapSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            }
        }
        token = "Bearer ${sessions.issue("cap-owner")}"
    }

    private fun node(slug: String, bytes: String) =
        """{"type":"FOLDER","slugToken":"$slug","orderIndex":0,"sealedContent":{"suiteId":"v1","bytes":"$bytes"}}"""

    @Test
    fun rejectsOverlargeSealedContentButAcceptsSmall() {
        // a tiny payload (6 bytes) is under the 8-byte cap → created
        given().header("Authorization", token).contentType(ContentType.JSON).body(node("cap-ok", "c2VhbGVk"))
            .post("/api/scopes/$scopeId/nodes").then().statusCode(200)

        // an over-large base64 string is rejected BEFORE decode (length bound) with a clean 400
        val big = "A".repeat(2000)
        given().header("Authorization", token).contentType(ContentType.JSON).body(node("cap-big", big))
            .post("/api/scopes/$scopeId/nodes").then().statusCode(400).body("code", `is`("CONTENT_TOO_LARGE"))
    }
}
