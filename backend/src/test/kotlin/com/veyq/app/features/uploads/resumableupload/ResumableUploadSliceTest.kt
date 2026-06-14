package com.veyq.app.features.uploads.resumableupload

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
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

@QuarkusTest
class ResumableUploadSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var sessions: SessionService

    private lateinit var ownerToken: String
    private lateinit var outsiderToken: String
    private val scopeId = "ru-scope"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            val now = Instant.now()
            if (users.findByEmail("ru-owner@example.com") != null) return@run
            users.persist(User().apply { id = "ru-owner"; email = "ru-owner@example.com"; displayName = "Owner"; serverRole = ServerRole.ServerMember; createdAt = now })
            users.persist(User().apply { id = "ru-outsider"; email = "ru-outsider@example.com"; displayName = "Outsider"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "ru-owner"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            grants.persist(ScopeGrant().apply { id = "ru-grant"; this.scopeId = this@ResumableUploadSliceTest.scopeId; userId = "ru-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "ru-ck"; this.scopeId = this@ResumableUploadSliceTest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "ru-mem"; this.scopeId = this@ResumableUploadSliceTest.scopeId; parentId = this@ResumableUploadSliceTest.scopeId; type = "MEMORY"; slugToken = "tok-mem"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "ru-folder"; this.scopeId = this@ResumableUploadSliceTest.scopeId; parentId = this@ResumableUploadSliceTest.scopeId; type = "FOLDER"; slugToken = "tok-folder"; orderIndex = 1; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
        }
        ownerToken = "Bearer ${sessions.issue("ru-owner")}"
        outsiderToken = "Bearer ${sessions.issue("ru-outsider")}"
    }

    private fun createBody() = """{"totalChunks":3,"mimeType":"video/mp4","byteSize":9000,"takenAt":"2026-01-01T10:00:00Z"}"""
    private fun chunkBody(bytes: String) = """{"frame":{"suiteId":"v1","bytes":"$bytes"}}"""

    @Test
    fun resumesAChunkedUploadAndFinalizes() {
        // media uploads only target a Memory/Chapter
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(createBody())
            .post("/api/scopes/$scopeId/nodes/ru-folder/uploads").then().statusCode(400).body("code", `is`("NOT_A_MEMORY"))

        // open a 3-chunk session
        val uploadId = given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(createBody())
            .post("/api/scopes/$scopeId/nodes/ru-mem/uploads").then().statusCode(200)
            .body("totalChunks", `is`(3)).body("status", `is`("OPEN")).extract().path<String>("uploadId")

        // an outsider cannot push chunks
        given().header("Authorization", outsiderToken).contentType(ContentType.JSON).body(chunkBody("Y2h1bmsx"))
            .put("/api/uploads/$uploadId/chunks/0").then().statusCode(403)

        // upload chunks 0 and 2, then "drop the connection" — chunk 1 is missing
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("Y2h1bmsw")).put("/api/uploads/$uploadId/chunks/0").then().statusCode(204)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("Y2h1bmsy")).put("/api/uploads/$uploadId/chunks/2").then().statusCode(204)
        // a re-PUT of an existing chunk is idempotent (the basis of resume)
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("Y2h1bmsw")).put("/api/uploads/$uploadId/chunks/0").then().statusCode(204)
        // an out-of-range index is rejected
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("eA")).put("/api/uploads/$uploadId/chunks/3").then().statusCode(400).body("code", `is`("INDEX_OUT_OF_RANGE"))

        // resume: ask which chunks landed, complete fails until the gap is filled
        given().header("Authorization", ownerToken).get("/api/uploads/$uploadId").then().statusCode(200)
            .body("status", `is`("OPEN")).body("receivedChunks", containsInAnyOrder(0, 2))
        given().header("Authorization", ownerToken).post("/api/uploads/$uploadId/complete").then().statusCode(409).body("code", `is`("INCOMPLETE_UPLOAD"))

        // send the missing chunk, then finalize
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("Y2h1bmsxMQ")).put("/api/uploads/$uploadId/chunks/1").then().statusCode(204)
        given().header("Authorization", ownerToken).get("/api/uploads/$uploadId").then().statusCode(200).body("receivedChunks", hasSize<Int>(3))
        given().header("Authorization", ownerToken).post("/api/uploads/$uploadId/complete").then().statusCode(200).body("status", `is`("COMPLETED"))

        // the stored frames download byte-identical (the server held opaque ciphertext)
        given().header("Authorization", ownerToken).get("/api/uploads/$uploadId/chunks/1").then().statusCode(200).body("frame.bytes", `is`("Y2h1bmsxMQ"))

        // no more chunks after finalize
        given().header("Authorization", ownerToken).contentType(ContentType.JSON).body(chunkBody("eA")).put("/api/uploads/$uploadId/chunks/0").then().statusCode(409).body("code", `is`("UPLOAD_NOT_OPEN"))
    }
}
