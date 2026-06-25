package com.veyq.app.features.memory.playlist

import com.veyq.app.generated.model.NodeType
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
import com.veyq.app.shared.keymgmt.ScopeKind
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/** uc.memory.playlist (ADR 0010, sealed) — create/list/feature/delete sealed playlist links on a Memory. */
@QuarkusTest
class PlaylistSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var token: String
    private val scopeId = "pl-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("pl-owner@example.com") == null) {
                users.persist(User().apply { id = "pl-owner"; email = "pl-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
                scopes.persist(Scope().apply { id = scopeId; ownerUserId = "pl-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "pl-grant"; this.scopeId = this@PlaylistSliceTest.scopeId; userId = "pl-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                contentKeys.persist(ScopeContentKey().apply { id = "pl-ck"; this.scopeId = this@PlaylistSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
                nodes.persist(Node().apply { id = "pl-node"; scopeId = "pl-scope"; parentId = "pl-scope"; type = NodeType.MEMORY; slugToken = "pl-n"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            }
        }
        token = "Bearer ${sessions.issue("pl-owner")}"
    }

    @Test
    fun playlistLifecycle() {
        val base = "/api/scopes/$scopeId/nodes/pl-node"
        given().header("Authorization", token).get("$base/playlists").then().statusCode(200).body("playlists.size()", `is`(0))

        // create one (sealed bytes are opaque to the server)
        val id1 = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"sealedContent":{"suiteId":"v1","bytes":"cGwx"},"featured":true}""")
            .post("$base/playlists").then().statusCode(200).body("featured", `is`(true)).extract().path<String>("id")

        // a second, also featured → the first must lose featured (at most one per node)
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"sealedContent":{"suiteId":"v1","bytes":"cGwy"},"featured":true}""")
            .post("$base/playlists").then().statusCode(200).body("featured", `is`(true))
        given().header("Authorization", token).get("$base/playlists").then().statusCode(200)
            .body("playlists.size()", `is`(2))
            .body("playlists.findAll { it.featured }.size()", `is`(1))

        // re-seal the first (e.g. edited link/title) + delete it
        given().header("Authorization", token).contentType(ContentType.JSON).body("""{"sealedContent":{"suiteId":"v1","bytes":"ZWRpdGVk"}}""")
            .patch("$base/playlists/$id1").then().statusCode(200).body("sealedContent.bytes", `is`("ZWRpdGVk"))
        given().header("Authorization", token).delete("$base/playlists/$id1").then().statusCode(204)
        given().header("Authorization", token).get("$base/playlists").then().statusCode(200).body("playlists.size()", `is`(1))
    }
}
