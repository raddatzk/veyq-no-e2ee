package com.veyq.app.features.uploads.blob

import com.veyq.app.shared.content.BlobChunk
import com.veyq.app.shared.content.BlobChunkRepository
import com.veyq.app.shared.content.BlobUpload
import com.veyq.app.shared.content.BlobUploadRepository
import com.veyq.app.shared.content.UploadStatus
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.storage.BlobStore
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.veyq.app.shared.keymgmt.ScopeKind

/** uc.uploads (GC) — a scheduled sweep reclaims abandoned OPEN uploads; fresh OPEN + COMPLETED survive. */
@QuarkusTest
class BlobUploadGcSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var uploads: BlobUploadRepository
    @Inject lateinit var chunks: BlobChunkRepository
    @Inject lateinit var blobs: BlobStore
    @Inject lateinit var handler: BlobUploadHandler

    private val now = Instant.now()

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("gc-bl@example.com") != null) return@run
            users.persist(User().apply { id = "gc-bl-user"; email = "gc-bl@example.com"; displayName = "U"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = "gc-bl-scope"; ownerUserId = "gc-bl-user"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            session("gc-stale", UploadStatus.OPEN, now.minus(48, ChronoUnit.HOURS))    // abandoned → purged
            session("gc-fresh", UploadStatus.OPEN, now)                                 // still in progress → kept
            session("gc-done", UploadStatus.COMPLETED, now.minus(48, ChronoUnit.HOURS)) // finalized blob → kept
        }
        // each session has one staged frame in the BlobStore
        blobs.put("gc-stale/0", byteArrayOf(1, 2, 3))
        blobs.put("gc-fresh/0", byteArrayOf(4, 5, 6))
        blobs.put("gc-done/0", byteArrayOf(7, 8, 9))
    }

    private fun session(id: String, status: UploadStatus, createdAt: Instant) {
        uploads.persist(BlobUpload().apply {
            this.id = id; scopeId = "gc-bl-scope"; dedupToken = "tok-$id"; totalChunks = 1; byteSize = 3
            this.status = status.name; createdBy = "gc-bl-user"; this.createdAt = createdAt
        })
        chunks.persist(BlobChunk().apply { this.id = "$id-c0"; uploadId = id; chunkIndex = 0; objectKey = "$id/0"; byteSize = 3; this.createdAt = now })
    }

    @Test
    fun purgesOnlyAbandonedOpenUploads() {
        val purged = handler.purgeStaleOpenUploads(now.minus(24, ChronoUnit.HOURS))

        assertEquals(1, purged, "only the stale OPEN upload is reclaimed")
        QuarkusTransaction.requiringNew().run {
            // the abandoned upload is gone: session, manifest rows and frame all reclaimed
            assertNull(uploads.findById("gc-stale"))
            assertEquals(0, chunks.listByUploadId("gc-stale").size)
            // the fresh OPEN and the COMPLETED upload survive untouched
            assertNotNull(uploads.findById("gc-fresh"))
            assertNotNull(uploads.findById("gc-done"))
        }
        assertNull(blobs.get("gc-stale/0"), "the abandoned upload's frame is deleted from the store")
        assertNotNull(blobs.get("gc-fresh/0"))
        assertNotNull(blobs.get("gc-done/0"))
    }
}
