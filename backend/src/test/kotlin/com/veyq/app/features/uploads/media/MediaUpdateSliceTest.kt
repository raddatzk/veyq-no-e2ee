package com.veyq.app.features.uploads.media

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

/** uc.uploads (update) — PATCH a medium's highlight flag + re-sealed metadata. */
@QuarkusTest
class MediaUpdateSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var medias: MediaRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var token: String
    private val scopeId = "mu-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("mu-owner@example.com") == null) {
                users.persist(User().apply { id = "mu-owner"; email = "mu-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
                scopes.persist(Scope().apply { id = scopeId; ownerUserId = "mu-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "mu-grant"; this.scopeId = this@MediaUpdateSliceTest.scopeId; userId = "mu-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                contentKeys.persist(ScopeContentKey().apply { id = "mu-ck"; this.scopeId = this@MediaUpdateSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
                nodes.persist(Node().apply { id = "mu-node"; scopeId = "mu-scope"; parentId = "mu-scope"; type = NodeType.MEMORY; slugToken = "mu-n"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
                blobs.persist(Blob().apply { id = "mu-b1"; scopeId = "mu-scope"; dedupToken = "mu-d1"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
                medias.persist(Media().apply { id = "mu-med"; scopeId = "mu-scope"; nodeId = "mu-node"; originalBlobId = "mu-b1"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = now })
            }
        }
        token = "Bearer ${sessions.issue("mu-owner")}"
    }

    @Test
    fun togglesHighlightAndReSealsMetadata() {
        // a fresh medium is not highlighted
        given().header("Authorization", token).get("/api/scopes/$scopeId/nodes/mu-node/media")
            .then().statusCode(200).body("media[0].highlighted", `is`(false))

        // PATCH highlight on
        given().header("Authorization", token).contentType(ContentType.JSON).body("""{"highlighted":true}""")
            .patch("/api/scopes/$scopeId/nodes/mu-node/media/mu-med")
            .then().statusCode(200).body("highlighted", `is`(true))

        // it sticks in the listing
        given().header("Authorization", token).get("/api/scopes/$scopeId/nodes/mu-node/media")
            .then().statusCode(200).body("media[0].highlighted", `is`(true))

        // PATCH a re-sealed metadata blob (e.g. an edited capture date)
        given().header("Authorization", token).contentType(ContentType.JSON).body("""{"sealedMeta":{"suiteId":"v1","bytes":"bmV3bWV0YQ"}}""")
            .patch("/api/scopes/$scopeId/nodes/mu-node/media/mu-med")
            .then().statusCode(200).body("sealedMeta.bytes", `is`("bmV3bWV0YQ")).body("highlighted", `is`(true))
    }
}
