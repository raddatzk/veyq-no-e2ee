package com.veyq.app.features.uploads.blob

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
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * uc.uploads (batched direct-to-store, ADR 0021) — opt-in e2e against a REAL S3 (MinIO). Proves the batched
 * fast path end-to-end over HTTP: ONE batch-start presigns every chunk of several blobs, the client PUTs the
 * raw frames straight to S3, ONE batch-finalize completes them all + attaches media, and ONE batch-presign-get
 * returns GET URLs for a whole gallery page. Gated on VEYQ_S3_E2E=1 + a MinIO on :19000 (same as
 * [PresignedUploadE2ETest]) so it never runs in normal CI.
 */
@QuarkusTest
@TestProfile(BlobBatchE2ETest.S3Profile::class)
// Skipped at the JUnit level (BEFORE Quarkus boots the S3 profile) unless opted in, so a normal `gradle test`
// with no MinIO doesn't even try to start the S3 backend. Run with: VEYQ_S3_E2E=1 + a MinIO on :19000.
@EnabledIfEnvironmentVariable(named = "VEYQ_S3_E2E", matches = "1")
class BlobBatchE2ETest {

    class S3Profile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.blob-store.backend" to "s3",
            "veyq.blob-store.s3-endpoint" to "http://localhost:19000",
            "veyq.blob-store.s3-access-key" to "veyqtest",
            "veyq.blob-store.s3-secret-key" to "veyqtestsecret",
            "veyq.blob-store.s3-bucket" to "veyq-e2e-batch",
        )
    }

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var grants: ScopeGrantRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var sessions: SessionService

    private val scopeId = "bb-scope"
    private val nodeId = "bb-mem"
    private lateinit var token: String
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (scopes.findById(scopeId) == null) {
                val now = Instant.now()
                users.persist(User().apply { id = "bb-owner"; email = "bb-owner@example.com"; displayName = "B"; serverRole = ServerRole.ServerMember; createdAt = now })
                scopes.persist(Scope().apply { id = scopeId; ownerUserId = "bb-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
                grants.persist(ScopeGrant().apply { id = "bb-grant"; this.scopeId = this@BlobBatchE2ETest.scopeId; userId = "bb-owner"; suiteId = "v1"; wrappedPrivateKey = "g".toByteArray(); createdAt = now })
                contentKeys.persist(ScopeContentKey().apply { id = "bb-ck"; this.scopeId = this@BlobBatchE2ETest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
                nodes.persist(Node().apply { id = nodeId; this.scopeId = this@BlobBatchE2ETest.scopeId; parentId = this@BlobBatchE2ETest.scopeId; type = NodeType.MEMORY; slugToken = "tb"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            }
        }
        token = "Bearer ${sessions.issue("bb-owner")}"
    }

    private fun putDirect(url: String, body: ByteArray) {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertTrue(res.statusCode() in 200..299, "presigned PUT failed: ${res.statusCode()}")
    }

    private fun getDirect(url: String): ByteArray {
        val res = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertTrue(res.statusCode() in 200..299, "presigned GET failed: ${res.statusCode()}")
        return res.body()
    }

    @Test
    fun batchUploadFinalizeDownloadRoundTrip() {
        // one photo = an original (2 frames) + a thumbnail (1 frame); a UNIQUE dedup token per run keeps it fresh
        val tag = "bb-${System.nanoTime()}"
        val orig = listOf("orig-frame-0".toByteArray(), "orig-frame-1".toByteArray())
        val thumb = listOf("thumb-frame-0".toByteArray())

        // 1. ONE batch-start opens both blobs and presigns every missing chunk
        val start = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"items":[
                {"dedupToken":"$tag-orig","totalChunks":2,"byteSize":24,"suiteId":"v1"},
                {"dedupToken":"$tag-thumb","totalChunks":1,"byteSize":13,"suiteId":"v1"}]}""")
            .post("/api/scopes/$scopeId/blob-uploads-batch").then().statusCode(200).extract().jsonPath()
        assertEquals("UPLOADING", start.getString("results[0].status"))
        assertEquals("UPLOADING", start.getString("results[1].status"))
        val origUpload = start.getString("results[0].uploadId")
        val thumbUpload = start.getString("results[1].uploadId")

        // 2. the client PUTs raw frames straight to S3 via the presigned URLs (no creds of its own)
        orig.forEachIndexed { i, f -> putDirect(start.getString("results[0].presignedPuts.find { it.index == $i }.url"), f) }
        putDirect(start.getString("results[1].presignedPuts[0].url"), thumb[0])

        // 3. ONE batch-finalize completes all uploads + attaches the medium with its thumbnail derivative
        val finalize = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"items":[{
                "original":{"uploadId":"$origUpload"},
                "derivatives":[{"kind":"THUMBNAIL","blob":{"uploadId":"$thumbUpload"}}],
                "sealedMeta":{"suiteId":"v1","bytes":"bWV0YQ"},"mimeType":"image/jpeg"}]}""")
            .post("/api/scopes/$scopeId/nodes/$nodeId/media-batch").then().statusCode(200).extract().jsonPath()
        assertEquals(true, finalize.getBoolean("results[0].ok"))
        val origBlob = finalize.getString("results[0].media.original.blobId")
        val thumbBlob = finalize.getString("results[0].media.derivatives[0].blob.blobId")
        assertEquals("THUMBNAIL", finalize.getString("results[0].media.derivatives[0].kind"))

        // the medium is listed on the node
        given().header("Authorization", token).get("/api/scopes/$scopeId/nodes/$nodeId/media").then().statusCode(200)
            .body("media.find { it.original.blobId == '$origBlob' }.derivatives[0].blob.blobId", `is`(thumbBlob))

        // 4. ONE batch-presign-get returns GET URLs for the whole page; frames come back byte-for-byte from S3
        val dl = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"blobIds":["$origBlob","$thumbBlob"]}""")
            .post("/api/scopes/$scopeId/blob-downloads-batch").then().statusCode(200).extract().jsonPath()
        assertEquals(2, dl.getList<Any>("results").size, "expected presigned chunks for both blobs")
        val origChunks = dl.getString("results.find { it.blobId == '$origBlob' }.chunks")
        assertTrue(origChunks != null, "original blob must be in the presign results")
        orig.forEachIndexed { i, f ->
            val url = dl.getString("results.find { it.blobId == '$origBlob' }.chunks.find { it.index == $i }.url")
            assertTrue(f.contentEquals(getDirect(url)), "orig chunk $i mismatch via presigned GET")
        }
        val thumbUrl = dl.getString("results.find { it.blobId == '$thumbBlob' }.chunks[0].url")
        assertTrue(thumb[0].contentEquals(getDirect(thumbUrl)), "thumb chunk mismatch via presigned GET")
    }

    @Test
    fun batchStartDedupsAlreadyStoredContentMidBatch() {
        val tag = "bbdedup-${System.nanoTime()}"
        // pre-store one blob via the full path
        val s1 = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"items":[{"dedupToken":"$tag","totalChunks":1,"byteSize":5,"suiteId":"v1"}]}""")
            .post("/api/scopes/$scopeId/blob-uploads-batch").then().statusCode(200).extract().jsonPath()
        val up = s1.getString("results[0].uploadId")
        putDirect(s1.getString("results[0].presignedPuts[0].url"), "hello".toByteArray())
        given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"items":[{"original":{"uploadId":"$up"},"sealedMeta":{"suiteId":"v1","bytes":"bWV0YQ"},"mimeType":"image/jpeg"}]}""")
            .post("/api/scopes/$scopeId/nodes/$nodeId/media-batch").then().statusCode(200).body("results[0].ok", `is`(true))

        // a second batch with the SAME content + a fresh one: dedup hit (READY) alongside a new upload
        val s2 = given().header("Authorization", token).contentType(ContentType.JSON)
            .body("""{"items":[
                {"dedupToken":"$tag","totalChunks":1,"byteSize":5,"suiteId":"v1"},
                {"dedupToken":"$tag-new","totalChunks":1,"byteSize":5,"suiteId":"v1"}]}""")
            .post("/api/scopes/$scopeId/blob-uploads-batch").then().statusCode(200).extract().jsonPath()
        assertEquals("READY", s2.getString("results[0].status"), "already-stored content dedups to READY")
        assertTrue(s2.getString("results[0].blobId") != null)
        assertEquals("UPLOADING", s2.getString("results[1].status"), "fresh content opens a new upload")
    }
}
