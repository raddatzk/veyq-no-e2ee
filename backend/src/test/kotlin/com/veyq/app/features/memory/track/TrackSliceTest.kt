package com.veyq.app.features.memory.track

import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
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
import org.hamcrest.Matchers.notNullValue
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/** uc.memory.track — list / add / remove a Memory's sealed GPX tracks, with the key-holder + node checks. */
@QuarkusTest
class TrackSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "trk-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("trk-owner@example.com") != null) return@run
            users.persist(User().apply { id = "trk-owner"; email = "trk-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "trk-out"; email = "trk-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "trk-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "trk-grant"; this.scopeId = this@TrackSliceTest.scopeId; userId = "trk-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "trk-ck"; this.scopeId = this@TrackSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            node("trk-folder", scopeId, "FOLDER", "s-tf", 0)
            node("trk-mem", scopeId, "MEMORY", "s-tm", 1)
        }
        ownerToken = "Bearer ${sessions.issue("trk-owner")}"
        outsiderToken = "Bearer ${sessions.issue("trk-out")}"
    }

    private fun node(id: String, parentId: String, type: String, slug: String, order: Int) {
        nodes.persist(Node().apply {
            this.id = id; scopeId = "trk-scope"; this.parentId = parentId; this.type = type
            slugToken = slug; orderIndex = order; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private val body = """{"sealedContent":{"suiteId":"v1","bytes":"Z3B4"}}"""

    @Test
    fun addsListsAndRemovesTracks() {
        // outsiders cannot touch tracks; folders have none; unknown suite rejected
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/trk-mem/tracks").then().statusCode(403)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/trk-folder/tracks").then().statusCode(400).body("code", `is`("NOT_A_MEMORY"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body("""{"sealedContent":{"suiteId":"v999","bytes":"Z3B4"}}""")
            .post("/api/scopes/$scopeId/nodes/trk-mem/tracks").then().statusCode(400).body("code", `is`("UNKNOWN_SUITE"))

        // add two tracks, then list them
        val id = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/trk-mem/tracks").then().statusCode(200)
            .body("sealedContent.bytes", `is`("Z3B4")).body("createdAt", notNullValue())
            .extract().path<String>("id")
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/trk-mem/tracks").then().statusCode(200)
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes/trk-mem/tracks").then().statusCode(200)
            .body("tracks.size()", `is`(2))

        // delete one; the other remains
        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/trk-mem/tracks/$id").then().statusCode(204)
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes/trk-mem/tracks").then().statusCode(200)
            .body("tracks.size()", `is`(1))
        // deleting an unknown track is a 404
        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/trk-mem/tracks/ghost").then().statusCode(404)
    }
}
