package com.veyq.app.features.uploads.blob

import com.veyq.app.generated.model.StartBlobRequest
import com.veyq.app.shared.content.ScopeContentKey
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeKind
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.storage.BlobStore
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 * uc.uploads (presigned direct-to-store, ADR 0021) — opt-in e2e against a REAL S3 (MinIO). Proves the full
 * fast path: the backend issues presigned PUT URLs, the client PUTs raw frames straight to S3 (no creds), and
 * complete stat-reconciles + promotes them to a blob. Gated on VEYQ_S3_E2E=1 + a MinIO on :19000 so it never
 * runs in normal CI:
 *   docker run -d --name veyq-minio-test -p 19000:9000 -e MINIO_ROOT_USER=veyqtest \
 *     -e MINIO_ROOT_PASSWORD=veyqtestsecret minio/minio server /data
 *   VEYQ_S3_E2E=1 ./gradlew test --tests '*PresignedUploadE2ETest'
 */
@QuarkusTest
@TestProfile(PresignedUploadE2ETest.S3Profile::class)
class PresignedUploadE2ETest {

    class S3Profile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.blob-store.backend" to "s3",
            "veyq.blob-store.s3-endpoint" to "http://localhost:19000",
            "veyq.blob-store.s3-access-key" to "veyqtest",
            "veyq.blob-store.s3-secret-key" to "veyqtestsecret",
            "veyq.blob-store.s3-bucket" to "veyq-e2e",
        )
    }

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var contentKeys: ScopeContentKeyRepository
    @Inject lateinit var handler: BlobUploadHandler
    @Inject lateinit var store: BlobStore

    private val scopeId = "ps-scope"

    @BeforeTest
    fun guardAndSeed() {
        assumeTrue(System.getenv("VEYQ_S3_E2E") == "1", "set VEYQ_S3_E2E=1 + run MinIO on :19000")
        QuarkusTransaction.requiringNew().run {
            if (scopes.findById(scopeId) != null) return@run
            val now = Instant.now()
            users.persist(User().apply { id = "ps-owner"; email = "ps-owner@example.com"; displayName = "P"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "ps-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            contentKeys.persist(ScopeContentKey().apply { id = "ps-ck"; this.scopeId = this@PresignedUploadE2ETest.scopeId; suiteId = "v1"; wrappedKey = "ck".toByteArray(); createdAt = now })
        }
    }

    @Test
    fun directToStoreRoundTrip() {
        val frames = listOf("frame-zero-bytes".toByteArray(), "frame-one-bytes!!".toByteArray())

        // 1. start a 2-chunk upload, declaring the frame suite (presigned frames carry none)
        val ticket = handler.startBlobAs(scopeId, "ps-owner", StartBlobRequest(dedupToken = "ps-dedup", totalChunks = 2, byteSize = 34, suiteId = "v1"))
        val uploadId = ticket.uploadId!!

        // 2. the backend hands out a presigned PUT URL per missing chunk (proves S3 signing works)
        val progress = handler.getBlobUploadAs(scopeId, uploadId)
        assertEquals(2, progress.presignedPuts?.size, "expected a presigned PUT per chunk")

        // 3. the client PUTs the RAW frame bytes straight to S3 with NO credentials of its own
        val http = HttpClient.newHttpClient()
        progress.presignedPuts!!.forEach { p ->
            val res = http.send(
                HttpRequest.newBuilder(URI.create(p.url)).PUT(HttpRequest.BodyPublishers.ofByteArray(frames[p.index])).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertTrue(res.statusCode() in 200..299, "presigned PUT failed: ${res.statusCode()}")
        }

        // 4. complete stat-reconciles the directly-uploaded objects and promotes them to a blob
        val done = handler.completeBlobUploadAs(scopeId, uploadId)
        assertEquals(2, done.totalChunks)

        // 5. the bytes really landed in S3 via the presigned PUTs, intact
        assertTrue(frames[0].contentEquals(store.get("$uploadId/0")), "chunk 0 mismatch in store")
        assertTrue(frames[1].contentEquals(store.get("$uploadId/1")), "chunk 1 mismatch in store")

        // 6. download fast path: the backend hands out a presigned GET per chunk and the raw frames come back
        //    DIRECTLY from S3, byte-for-byte (the client opens them with openFrame).
        val gets = handler.getBlobPresignedChunksAs(scopeId, done.blobId)
        assertEquals(2, gets.chunks.size, "expected a presigned GET per chunk")
        gets.chunks.sortedBy { it.index }.forEach { c ->
            val res = http.send(HttpRequest.newBuilder(URI.create(c.url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            assertTrue(res.statusCode() in 200..299, "presigned GET failed: ${res.statusCode()}")
            assertTrue(frames[c.index].contentEquals(res.body()), "chunk ${c.index} mismatch via presigned GET")
        }
    }

    @Test
    fun resumeSkipsChunksAlreadyInTheStore() {
        val http = HttpClient.newHttpClient()
        val ticket = handler.startBlobAs(scopeId, "ps-owner", StartBlobRequest(dedupToken = "ps-resume", totalChunks = 2, byteSize = 21, suiteId = "v1"))
        val uploadId = ticket.uploadId!!

        // upload ONLY chunk 0 directly to the store
        val first = handler.getBlobUploadAs(scopeId, uploadId).presignedPuts!!.first { it.index == 0 }
        http.send(
            HttpRequest.newBuilder(URI.create(first.url)).PUT(HttpRequest.BodyPublishers.ofByteArray("a".repeat(10).toByteArray())).build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        // resume: the server stats the store, counts chunk 0 as received, and presigns only the missing chunk 1
        val resumed = handler.getBlobUploadAs(scopeId, uploadId)
        assertEquals(listOf(0), resumed.receivedChunks, "chunk 0 already in the store must count as received")
        assertEquals(listOf(1), resumed.presignedPuts?.map { it.index }, "only the missing chunk should be re-presigned")
    }
}
