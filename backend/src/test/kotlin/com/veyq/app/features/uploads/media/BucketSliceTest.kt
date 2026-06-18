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

/** uc.contributions (ADR 0023) — contribution buckets: synthesized primary, create/move/rename/delete sub-bucket. */
@QuarkusTest
class BucketSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var medias: MediaRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var token: String
    private val scopeId = "bk-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("bk-owner@example.com") == null) {
                users.persist(User().apply { id = "bk-owner"; email = "bk-owner@example.com"; displayName = "O"; serverRole = ServerRole.ServerMember; createdAt = now })
                scopes.persist(Scope().apply { id = scopeId; ownerUserId = "bk-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "bk-grant"; this.scopeId = this@BucketSliceTest.scopeId; userId = "bk-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                contentKeys.persist(ScopeContentKey().apply { id = "bk-ck"; this.scopeId = this@BucketSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
                nodes.persist(Node().apply { id = "bk-node"; scopeId = "bk-scope"; parentId = "bk-scope"; type = NodeType.MEMORY; slugToken = "bk-n"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
                blobs.persist(Blob().apply { id = "bk-b1"; scopeId = "bk-scope"; dedupToken = "bk-d1"; totalChunks = 1; byteSize = 9; suiteId = "v1"; createdAt = now })
                // a medium attributed to the owner, sitting in their (primary) bucket
                medias.persist(Media().apply { id = "bk-med"; scopeId = "bk-scope"; nodeId = "bk-node"; originalBlobId = "bk-b1"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdBy = "user:bk-owner"; bucketId = null; createdAt = now })
            }
        }
        token = "Bearer ${sessions.issue("bk-owner")}"
    }

    @Test
    fun bucketLifecycle() {
        val base = "/api/scopes/$scopeId/nodes/bk-node"

        // the owner's primary bucket is synthesized and counts the un-bucketed medium
        given().header("Authorization", token).get("$base/buckets")
            .then().statusCode(200)
            .body("buckets.size()", `is`(1))
            .body("buckets[0].primary", `is`(true))
            .body("buckets[0].ownerKey", `is`("user:bk-owner"))
            .body("buckets[0].mediaCount", `is`(1))

        // create a sub-bucket (sealed name "name")
        val subId = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"sealedName":{"suiteId":"v1","bytes":"bmFtZQ"}}""")
            .post("$base/buckets")
            .then().statusCode(200).body("primary", `is`(false)).body("ownerKey", `is`("user:bk-owner"))
            .extract().path<String>("id")

        // move the medium into the sub-bucket → primary now empty, sub holds 1
        given().header("Authorization", token).contentType(ContentType.JSON).body("""{"bucketId":"$subId"}""")
            .patch("$base/media/bk-med").then().statusCode(200).body("bucketId", `is`(subId))
        given().header("Authorization", token).get("$base/buckets").then().statusCode(200)
            .body("buckets.find { it.primary }.mediaCount", `is`(0))
            .body("buckets.find { it.id == '$subId' }.mediaCount", `is`(1))

        // rename the sub-bucket
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"sealedName":{"suiteId":"v1","bytes":"cmVuYW1lZA"}}""")
            .patch("$base/buckets/$subId").then().statusCode(200).body("sealedName.bytes", `is`("cmVuYW1lZA"))

        // delete the sub-bucket → its media fall back to the primary
        given().header("Authorization", token).delete("$base/buckets/$subId").then().statusCode(204)
        given().header("Authorization", token).get("$base/buckets").then().statusCode(200)
            .body("buckets.size()", `is`(1))
            .body("buckets[0].primary", `is`(true))
            .body("buckets[0].mediaCount", `is`(1))
    }
}
