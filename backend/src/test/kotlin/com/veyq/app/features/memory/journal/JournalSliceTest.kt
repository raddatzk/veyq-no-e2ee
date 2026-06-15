package com.veyq.app.features.memory.journal

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
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "jnl-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
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
            this.id = id; scopeId = "jnl-scope"; this.parentId = parentId; this.type = type
            slugToken = slug; orderIndex = order; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private fun get(token: String, nodeId: String) =
        given().header("Authorization", token).get("/api/scopes/$scopeId/nodes/$nodeId/journal").then()

    private fun put(token: String, nodeId: String, body: String) =
        given().header("Authorization", token).contentType(ContentType.JSON).body(body)
            .put("/api/scopes/$scopeId/nodes/$nodeId/journal").then()

    @Test
    fun readsAndReplacesJournal() {
        // outsiders cannot read or write
        get(outsiderToken, "jmem").statusCode(403)
        put(outsiderToken, "jmem", """{"sealedContent":{"suiteId":"v1","bytes":"bWQ"}}""").statusCode(403)

        // a Memory with no journal yet reports a null body
        get(ownerToken, "jmem").statusCode(200).body("nodeId", `is`("jmem")).body("sealedContent", nullValue())

        // write the journal, then read it back
        put(ownerToken, "jmem", """{"sealedContent":{"suiteId":"v1","bytes":"bWQ"}}""")
            .statusCode(200).body("sealedContent.bytes", `is`("bWQ")).body("updatedAt", notNullValue())
        get(ownerToken, "jmem").statusCode(200).body("sealedContent.bytes", `is`("bWQ"))

        // a second write replaces the body
        put(ownerToken, "jmem", """{"sealedContent":{"suiteId":"v1","bytes":"bmV3"}}""")
            .statusCode(200).body("sealedContent.bytes", `is`("bmV3"))

        // a Folder has no journal
        put(ownerToken, "jfolder", """{"sealedContent":{"suiteId":"v1","bytes":"bWQ"}}""")
            .statusCode(400).body("code", `is`("NOT_A_MEMORY"))

        // unknown suite is rejected
        put(ownerToken, "jmem", """{"sealedContent":{"suiteId":"v999","bytes":"bWQ"}}""")
            .statusCode(400).body("code", `is`("UNKNOWN_SUITE"))
    }
}
