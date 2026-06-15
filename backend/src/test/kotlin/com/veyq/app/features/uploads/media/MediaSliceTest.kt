package com.veyq.app.features.uploads.media

import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobRepository
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class MediaSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "me-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("me-owner@example.com") != null) return@run
            users.persist(User().apply { id = "me-owner"; email = "me-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "me-outsider"; email = "me-outsider@example.com"; displayName = "Outsider"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "me-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "me-grant"; this.scopeId = this@MediaSliceTest.scopeId; userId = "me-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "me-mem"; this.scopeId = this@MediaSliceTest.scopeId; parentId = this@MediaSliceTest.scopeId; type = "MEMORY"; slugToken = "tm"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "me-folder"; this.scopeId = this@MediaSliceTest.scopeId; parentId = this@MediaSliceTest.scopeId; type = "FOLDER"; slugToken = "tf"; orderIndex = 1; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            // two already-stored content blobs (the original photo + its thumbnail)
            blobs.persist(Blob().apply { id = "me-orig"; this.scopeId = this@MediaSliceTest.scopeId; dedupToken = "tok-orig"; totalChunks = 3; byteSize = 9000; suiteId = "v1"; createdAt = now })
            blobs.persist(Blob().apply { id = "me-thumb"; this.scopeId = this@MediaSliceTest.scopeId; dedupToken = "tok-thumb"; totalChunks = 1; byteSize = 80; suiteId = "v1"; createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("me-owner")}"
        outsiderToken = "Bearer ${sessions.issue("me-outsider")}"
    }

    private fun mediaBody(orig: String = "me-orig", withThumb: Boolean = true, suite: String = "v1"): String {
        val derivs = if (withThumb) ""","derivatives":[{"kind":"THUMBNAIL","blobId":"me-thumb"}]""" else ""
        // sealedMeta is opaque ciphertext to the server ({takenAt, lat?, lng?} sealed client-side).
        return """{"originalBlobId":"$orig","sealedMeta":{"suiteId":"$suite","bytes":"bWV0YQ"},"mimeType":"image/jpeg"$derivs}"""
    }

    @Test
    fun attachesBlobsAsMediaWithDerivativesAndLists() {
        // only a key-holder may attach; media live in a Memory/Chapter, not a Folder; the blob must exist
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(mediaBody())
            .post("/api/scopes/$scopeId/nodes/me-mem/media").then().statusCode(403)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody())
            .post("/api/scopes/$scopeId/nodes/me-folder/media").then().statusCode(400).body("code", `is`("NOT_A_MEMORY"))
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody(orig = "ghost"))
            .post("/api/scopes/$scopeId/nodes/me-mem/media").then().statusCode(404).body("code", `is`("BLOB_NOT_FOUND"))
        // an unknown crypto suite on the sealed metadata is rejected
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody(suite = "v999"))
            .post("/api/scopes/$scopeId/nodes/me-mem/media").then().statusCode(400).body("code", `is`("UNKNOWN_SUITE"))

        // attach the photo (with a thumbnail derivative); the response references both blobs + the sealed meta
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody())
            .post("/api/scopes/$scopeId/nodes/me-mem/media").then().statusCode(200)
            .body("original.blobId", `is`("me-orig")).body("original.totalChunks", `is`(3))
            .body("sealedMeta.suiteId", `is`("v1")).body("sealedMeta.bytes", `is`("bWV0YQ"))
            .body("derivatives[0].kind", `is`("THUMBNAIL")).body("derivatives[0].blob.blobId", `is`("me-thumb"))
        // a second photo without a thumbnail
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(mediaBody(withThumb = false))
            .post("/api/scopes/$scopeId/nodes/me-mem/media").then().statusCode(200)

        // the gallery lists both in upload order (the client sorts by decrypted takenAt), with their blob refs
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/nodes/me-mem/media").then().statusCode(200)
            .body("media.size()", `is`(2))
            .body("media[0].original.blobId", `is`("me-orig"))
            .body("media[0].derivatives[0].kind", `is`("THUMBNAIL"))
            .body("media[1].derivatives.size()", `is`(0))
    }
}
