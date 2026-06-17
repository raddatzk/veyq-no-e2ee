package com.veyq.app.features.memory.journal

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
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import com.veyq.app.shared.keymgmt.ScopeKind

/** uc.memory.journal — read/replace a Memory's sealed journal, with the key-holder + node-type checks. */
@QuarkusTest
class JournalSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "jnl-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("jnl-owner@example.com") != null) return@run
            users.persist(User().apply { id = "jnl-owner"; email = "jnl-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "jnl-out"; email = "jnl-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "jnl-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "jnl-grant"; this.scopeId = this@JournalSliceTest.scopeId; userId = "jnl-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "jnl-ck"; this.scopeId = this@JournalSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            node("jfolder", scopeId, "FOLDER", "s-jf", 0)
            node("jmem", scopeId, "MEMORY", "s-jm", 1)
        }
        ownerToken = "Bearer ${sessions.issue("jnl-owner")}"
        outsiderToken = "Bearer ${sessions.issue("jnl-out")}"
    }

    private fun node(id: String, parentId: String, type: String, slug: String, order: Int) {
        nodes.persist(Node().apply {
            this.id = id; scopeId = "jnl-scope"; this.parentId = parentId; this.type = NodeType.valueOf(type)
            slugToken = slug; orderIndex = order; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private fun getMd(token: String, nodeId: String) =
        given().header("Authorization", token).get("/api/scopes/$scopeId/nodes/$nodeId/journal").then()

    private fun getLive(token: String, nodeId: String) =
        given().header("Authorization", token).get("/api/scopes/$scopeId/nodes/$nodeId/journal/live").then()

    private fun append(token: String, nodeId: String, bytes: String, suite: String = "v1") =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"sealedUpdate":{"suiteId":"$suite","bytes":"$bytes"}}""")
            .post("/api/scopes/$scopeId/nodes/$nodeId/journal/updates").then()

    private fun compact(token: String, nodeId: String, snapshot: String, markdown: String, coveredSeq: Int, suite: String = "v1") =
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"snapshot":{"suiteId":"$suite","bytes":"$snapshot"},"markdown":{"suiteId":"$suite","bytes":"$markdown"},"coveredSeq":$coveredSeq}""")
            .put("/api/scopes/$scopeId/nodes/$nodeId/journal/snapshot").then()

    @Test
    fun appendsUpdatesThenCompactsOperatorBlind() {
        // outsiders cannot read or write any projection
        getMd(outsiderToken, "jmem").statusCode(403)
        getLive(outsiderToken, "jmem").statusCode(403)
        append(outsiderToken, "jmem", "dTE").statusCode(403)

        // fresh journal: null Markdown body, empty CRDT state
        getMd(ownerToken, "jmem").statusCode(200).body("nodeId", `is`("jmem")).body("sealedContent", nullValue())
        getLive(ownerToken, "jmem").statusCode(200).body("snapshot", nullValue()).body("baseSeq", `is`(0)).body("updates.size()", `is`(0))

        // append two sealed updates → server assigns monotonic seqs, tagged with the author
        append(ownerToken, "jmem", "dTE").statusCode(200).body("seq", `is`(1))
        append(ownerToken, "jmem", "dTI").statusCode(200).body("seq", `is`(2))
        getLive(ownerToken, "jmem").statusCode(200)
            .body("updates.size()", `is`(2))
            .body("updates[0].seq", `is`(1)).body("updates[0].sealedUpdate.bytes", `is`("dTE")).body("updates[0].authorId", `is`("jnl-owner"))
            .body("updates[1].seq", `is`(2))
        // the Markdown projection is still empty until a compaction writes it
        getMd(ownerToken, "jmem").body("sealedContent", nullValue())

        // compact through seq 2: writes the snapshot + Markdown projection and prunes the folded updates
        compact(ownerToken, "jmem", "c25w", "bWQ", 2).statusCode(200).body("sealedContent.bytes", `is`("bWQ"))
        getLive(ownerToken, "jmem").statusCode(200)
            .body("snapshot.bytes", `is`("c25w")).body("baseSeq", `is`(2)).body("updates.size()", `is`(0))
        getMd(ownerToken, "jmem").statusCode(200).body("sealedContent.bytes", `is`("bWQ")).body("updatedAt", notNullValue())

        // a later append continues from seq 3 on top of the snapshot
        append(ownerToken, "jmem", "dTM").statusCode(200).body("seq", `is`(3))
        getLive(ownerToken, "jmem").body("snapshot.bytes", `is`("c25w")).body("updates.size()", `is`(1)).body("updates[0].seq", `is`(3))
    }

    @Test
    fun rejectsBadNodeSuiteAndSeq() {
        // a Folder has no journal
        append(ownerToken, "jfolder", "dTE").statusCode(400).body("code", `is`("NOT_A_MEMORY"))
        // unknown suite is rejected
        append(ownerToken, "jmem", "dTE", suite = "v999").statusCode(400).body("code", `is`("UNKNOWN_SUITE"))
        // a compaction can't cover a seq that was never handed out
        compact(ownerToken, "jmem", "c25w", "bWQ", 9999).statusCode(400).body("code", `is`("BAD_SEQ"))
    }
}
