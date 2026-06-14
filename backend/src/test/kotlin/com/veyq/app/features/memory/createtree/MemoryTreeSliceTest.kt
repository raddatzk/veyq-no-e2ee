package com.veyq.app.features.memory.createtree

import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
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
import org.hamcrest.CoreMatchers.nullValue
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class MemoryTreeSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "mt-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("mt-owner@example.com") == null) {
                users.persist(User().apply { id = "mt-owner"; email = "mt-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
                users.persist(User().apply { id = "mt-outsider"; email = "mt-outsider@example.com"; displayName = "Outsider"; serverRole = ServerRole.ServerMember; createdAt = now })
                // a personal scope (no tenant → no membership gate) the owner holds the key for
                scopes.persist(Scope().apply { id = scopeId; ownerUserId = "mt-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "mt-grant"; this.scopeId = this@MemoryTreeSliceTest.scopeId; userId = "mt-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                // a foreign scope (owned by someone else) + a node in it, to prove cross-scope parents are rejected
                scopes.persist(Scope().apply { id = "mt-other"; ownerUserId = "mt-outsider"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "o".toByteArray(); createdAt = now })
                nodes.persist(Node().apply { id = "mt-foreign-node"; this.scopeId = "mt-other"; parentId = "mt-other"; type = "FOLDER"; slugToken = "tok-x"; orderIndex = 0; suiteId = "v1"; sealedContent = "s".toByteArray(); createdAt = now })
            }
        }
        ownerToken = "Bearer ${sessions.issue("mt-owner")}"
        outsiderToken = "Bearer ${sessions.issue("mt-outsider")}"
    }

    private fun body(type: String, slugToken: String, orderIndex: Int, parentId: String? = null): String {
        val parent = if (parentId == null) "" else """"parentId":"$parentId","""
        return """{$parent"type":"$type","slugToken":"$slugToken","orderIndex":$orderIndex,"sealedContent":{"suiteId":"v1","bytes":"c2VhbGVk"}}"""
    }

    private fun create(token: String, type: String, slug: String, order: Int, parentId: String? = null) =
        given().header("Authorization", token).contentType(ContentType.JSON).body(body(type, slug, order, parentId))
            .post("/api/scopes/$scopeId/nodes")

    @Test
    fun buildsTreeWithContainmentAndSlugInvariants() {
        // only a key-holder may touch the tree
        create(outsiderToken, "FOLDER", "tok-urlaube", 0).then().statusCode(403).body("code", `is`("NOT_A_KEY_HOLDER"))

        // a folder at the root, a memory inside it, a chapter inside the memory — the happy path
        val folderId = create(ownerToken, "FOLDER", "tok-urlaube", 0).then().statusCode(200)
            .body("type", `is`("FOLDER")).body("parentId", nullValue()).extract().path<String>("id")
        val memId = create(ownerToken, "MEMORY", "tok-italien", 0, folderId).then().statusCode(200)
            .body("type", `is`("MEMORY")).body("parentId", `is`(folderId)).extract().path<String>("id")
        create(ownerToken, "CHAPTER", "tok-tag1", 0, memId).then().statusCode(200)
            .body("type", `is`("CHAPTER")).body("parentId", `is`(memId))

        // containment invariants
        create(ownerToken, "CHAPTER", "tok-c", 1).then().statusCode(400).body("code", `is`("INVALID_CONTAINMENT")) // chapter at root
        create(ownerToken, "FOLDER", "tok-f", 1, memId).then().statusCode(400).body("code", `is`("INVALID_CONTAINMENT")) // folder in memory
        create(ownerToken, "MEMORY", "tok-m", 1, memId).then().statusCode(400).body("code", `is`("INVALID_CONTAINMENT")) // memory in memory

        // slug uniqueness is per parent: a dup at the root is rejected, the same token under a different parent is fine
        create(ownerToken, "MEMORY", "tok-urlaube", 1).then().statusCode(409).body("code", `is`("SLUG_EXISTS"))
        create(ownerToken, "MEMORY", "tok-urlaube", 1, folderId).then().statusCode(200)

        // a parent in another scope is rejected
        create(ownerToken, "MEMORY", "tok-cross", 2, "mt-foreign-node").then().statusCode(400).body("code", `is`("CROSS_SCOPE_PARENT"))

        // the list returns the graph; the root folder reports a null parent
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes").then().statusCode(200)
            .body("nodes.size()", `is`(4))
            .body("nodes.find { it.id == '$folderId' }.parentId", nullValue())
            .body("nodes.find { it.type == 'CHAPTER' }.parentId", `is`(memId))
    }
}
