package com.veyq.app.features.people.participant

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
import org.hamcrest.Matchers.notNullValue
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/** uc.people.manage-participants — sealed participant payload CRUD with key-holder + node checks. */
@QuarkusTest
class ParticipantSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "part-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("part-owner@example.com") != null) return@run
            users.persist(User().apply { id = "part-owner"; email = "part-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "part-out"; email = "part-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "part-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "part-grant"; this.scopeId = this@ParticipantSliceTest.scopeId; userId = "part-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "part-ck"; this.scopeId = this@ParticipantSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            node("part-folder", scopeId, "FOLDER", "s-pf", 0)
            node("part-mem", scopeId, "MEMORY", "s-pm", 1)
        }
        ownerToken = "Bearer ${sessions.issue("part-owner")}"
        outsiderToken = "Bearer ${sessions.issue("part-out")}"
    }

    private fun node(id: String, parentId: String, type: String, slug: String, order: Int) {
        nodes.persist(Node().apply {
            this.id = id; scopeId = "part-scope"; this.parentId = parentId; this.type = NodeType.valueOf(type)
            slugToken = slug; orderIndex = order; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private val body = """{"sealedPayload":{"suiteId":"v1","bytes":"cGFydA"}}"""

    @Test
    fun addsListsUpdatesAndRemovesParticipants() {
        // outsiders cannot touch participants; folders have none; unknown suite rejected
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/part-mem/participants").then().statusCode(403)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/part-folder/participants").then().statusCode(400).body("code", `is`("NOT_A_MEMORY"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body("""{"sealedPayload":{"suiteId":"v999","bytes":"cGFydA"}}""")
            .post("/api/scopes/$scopeId/nodes/part-mem/participants").then().statusCode(400).body("code", `is`("UNKNOWN_SUITE"))

        // add two opaque payloads; the server returns the same sealed bytes but never inspects them
        val id = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/part-mem/participants").then().statusCode(200)
            .body("sealedPayload.bytes", `is`("cGFydA")).body("createdAt", notNullValue())
            .extract().path<String>("id")
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(body)
            .post("/api/scopes/$scopeId/nodes/part-mem/participants").then().statusCode(200)
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes/part-mem/participants").then().statusCode(200)
            .body("participants.size()", `is`(2))

        // update is a ciphertext swap, e.g. rename or link/unlink a user in the sealed payload
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body("""{"sealedPayload":{"suiteId":"v1","bytes":"cmVuYW1lZA"}}""")
            .patch("/api/scopes/$scopeId/nodes/part-mem/participants/$id").then().statusCode(200)
            .body("sealedPayload.bytes", `is`("cmVuYW1lZA"))

        // delete one; the other remains
        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/part-mem/participants/$id").then().statusCode(204)
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes/part-mem/participants").then().statusCode(200)
            .body("participants.size()", `is`(1))
        given().header("Authorization", ownerToken).delete("/api/scopes/$scopeId/nodes/part-mem/participants/ghost").then().statusCode(404)
    }
}
