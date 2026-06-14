package com.veyq.app.features.uploads.blob

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
import org.hamcrest.Matchers.containsInAnyOrder
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class BlobUploadSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "bl-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("bl-owner@example.com") != null) return@run
            users.persist(User().apply { id = "bl-owner"; email = "bl-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "bl-outsider"; email = "bl-outsider@example.com"; displayName = "Outsider"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "bl-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "bl-grant"; this.scopeId = this@BlobUploadSliceTest.scopeId; userId = "bl-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "bl-ck"; this.scopeId = this@BlobUploadSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("bl-owner")}"
        outsiderToken = "Bearer ${sessions.issue("bl-outsider")}"
    }

    private fun startBody(token: String, total: Int = 2, size: Int = 16) = """{"dedupToken":"$token","totalChunks":$total,"byteSize":$size}"""
    private fun chunkBody(bytes: String) = """{"frame":{"suiteId":"v1","bytes":"$bytes"}}"""

    @Test
    fun uploadsDeduplicatesAndDownloadsABlob() {
        // outsiders can't start an upload
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(startBody("tok-a"))
            .post("/api/scopes/$scopeId/blobs").then().statusCode(403)

        // new content → UPLOADING with an uploadId
        val uploadId = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(startBody("tok-a"))
            .post("/api/scopes/$scopeId/blobs").then().statusCode(200)
            .body("status", `is`("UPLOADING")).extract().path<String>("uploadId")

        // push two frames, then finalize
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("ZnJhbWUw")).put("/api/scopes/$scopeId/blob-uploads/$uploadId/chunks/0").then().statusCode(204)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("ZnJhbWUx")).put("/api/scopes/$scopeId/blob-uploads/$uploadId/chunks/1").then().statusCode(204)
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/blob-uploads/$uploadId").then().statusCode(200)
            .body("receivedChunks", containsInAnyOrder(0, 1))
        val blobId = given().header("Authorization", ownerToken).post("/api/scopes/$scopeId/blob-uploads/$uploadId/complete").then().statusCode(200)
            .body("totalChunks", `is`(2)).extract().path<String>("blobId")

        // re-completing is idempotent
        given().header("Authorization", ownerToken).post("/api/scopes/$scopeId/blob-uploads/$uploadId/complete").then().statusCode(200).body("blobId", `is`(blobId))

        // the same content now dedups: start → READY with the existing blobId, no upload needed
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(startBody("tok-a"))
            .post("/api/scopes/$scopeId/blobs").then().statusCode(200)
            .body("status", `is`("READY")).body("blobId", `is`(blobId)).body("totalChunks", `is`(2))

        // the finalized frames download byte-identical
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/blobs/$blobId/chunks/0").then().statusCode(200).body("frame.bytes", `is`("ZnJhbWUw"))
        given().header("Authorization", ownerToken).get("/api/scopes/$scopeId/blobs/$blobId/chunks/1").then().statusCode(200).body("frame.bytes", `is`("ZnJhbWUx"))

        // a missing chunk before finalize blocks completion
        val u2 = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(startBody("tok-b", total = 2))
            .post("/api/scopes/$scopeId/blobs").then().statusCode(200).extract().path<String>("uploadId")
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("eA")).put("/api/scopes/$scopeId/blob-uploads/$u2/chunks/0").then().statusCode(204)
        given().header("Authorization", ownerToken).post("/api/scopes/$scopeId/blob-uploads/$u2/complete").then().statusCode(409).body("code", `is`("INCOMPLETE_UPLOAD"))
    }
}
