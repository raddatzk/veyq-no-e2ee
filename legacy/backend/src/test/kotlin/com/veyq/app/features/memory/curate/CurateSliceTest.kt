package com.veyq.app.features.memory.curate

import com.veyq.app.generated.model.NodeType
import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaRepository
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import com.veyq.app.shared.keymgmt.ScopeKind

/** uc.memory.curate — rename / move / reorder / cover with the structural re-checks. */
@QuarkusTest
class CurateSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var medias: MediaRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "cur-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("cur-owner@example.com") != null) return@run
            users.persist(User().apply { id = "cur-owner"; email = "cur-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "cur-out"; email = "cur-out@example.com"; displayName = "X"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "cur-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "cur-grant"; this.scopeId = this@CurateSliceTest.scopeId; userId = "cur-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "cur-ck"; this.scopeId = this@CurateSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            // folderA(root) > folderA2(folder), memoryM(memory) > chapterC(chapter) ; folderB(root)
            node("fa", scopeId, "FOLDER", "s-fa", 0)
            node("fa2", "fa", "FOLDER", "s-fa2", 0)
            node("fb", scopeId, "FOLDER", "s-fb", 1)
            node("mm", "fa", "MEMORY", "s-m", 0)
            node("cc", "mm", "CHAPTER", "s-c", 0)
            // a media on mm and one on cc (for the cover checks)
            blobs.persist(Blob().apply { id = "b1"; this.scopeId = this@CurateSliceTest.scopeId; dedupToken = "d1"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
            blobs.persist(Blob().apply { id = "b2"; this.scopeId = this@CurateSliceTest.scopeId; dedupToken = "d2"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
            medias.persist(Media().apply { id = "med1"; this.scopeId = this@CurateSliceTest.scopeId; nodeId = "mm"; originalBlobId = "b1"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = now })
            medias.persist(Media().apply { id = "med2"; this.scopeId = this@CurateSliceTest.scopeId; nodeId = "cc"; originalBlobId = "b2"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("cur-owner")}"
        outsiderToken = "Bearer ${sessions.issue("cur-out")}"
    }

    private fun node(id: String, parentId: String, type: String, slug: String, order: Int) {
        nodes.persist(Node().apply {
            this.id = id; scopeId = "cur-scope"; this.parentId = parentId; this.type = NodeType.valueOf(type)
            slugToken = slug; orderIndex = order; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = Instant.now()
        })
    }

    private fun patch(token: String, nodeId: String, body: String) =
        given().header("Authorization", token).contentType(ContentType.JSON).body(body)
            .patch("/api/scopes/$scopeId/nodes/$nodeId").then()

    @Test
    fun curatesNodesWithStructuralChecks() {
        // outsiders cannot curate
        patch(outsiderToken, "mm", """{"orderIndex":3}""").statusCode(403)

        // rename: new sealed name + slug token (same parent)
        patch(ownerToken, "mm", """{"sealedContent":{"suiteId":"v1","bytes":"bmV3"},"slugToken":"s-m2"}""")
            .statusCode(200).body("slugToken", `is`("s-m2"))
        // a rename that collides with a sibling slug is rejected (fb → fa's slug at root)
        patch(ownerToken, "fb", """{"sealedContent":{"suiteId":"v1","bytes":"eA"},"slugToken":"s-fa"}""")
            .statusCode(409).body("code", `is`("SLUG_EXISTS"))

        // move mm from fa to fb (slug recomputed under fb)
        patch(ownerToken, "mm", """{"newParentId":"fb","slugToken":"s-m-b"}""")
            .statusCode(200).body("parentId", `is`("fb"))
        // move to the scope root works too (root nodes report parentId = null)
        patch(ownerToken, "fa2", """{"newParentId":"root","slugToken":"s-fa2r"}""")
            .statusCode(200).body("parentId", org.hamcrest.Matchers.nullValue())
        // move fa2 back under fa, then moving fa INTO fa2 is a cycle
        patch(ownerToken, "fa2", """{"newParentId":"fa","slugToken":"s-fa2"}""").statusCode(200)
        patch(ownerToken, "fa", """{"newParentId":"fa2","slugToken":"s-fa-x"}""")
            .statusCode(409).body("code", `is`("NODE_CYCLE"))
        // a folder cannot move under a memory (containment)
        patch(ownerToken, "fa", """{"newParentId":"mm","slugToken":"s-fa-y"}""")
            .statusCode(400).body("code", `is`("INVALID_CONTAINMENT"))

        // reorder
        patch(ownerToken, "fa", """{"orderIndex":7}""").statusCode(200).body("orderIndex", `is`(7))

        // cover: a media of mm pins; a media of another node and a folder are rejected
        patch(ownerToken, "mm", """{"coverMediaId":"med1"}""").statusCode(200).body("coverMediaId", `is`("med1"))
        patch(ownerToken, "mm", """{"coverMediaId":"med2"}""").statusCode(400).body("code", `is`("COVER_NOT_OWN_MEDIA"))
        patch(ownerToken, "fa", """{"coverMediaId":"med1"}""").statusCode(400).body("code", `is`("NOT_A_MEMORY"))
    }
}
