package com.veyq.app.features.uploads.resumableupload

import com.veyq.app.shared.content.MediaChunk
import com.veyq.app.shared.content.MediaChunkRepository
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.UploadSession
import com.veyq.app.shared.content.UploadSessionRepository
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

@QuarkusTest
class UploadGcSliceTest {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var uploads: UploadSessionRepository
    @Inject lateinit var chunks: MediaChunkRepository
    @Inject lateinit var blobs: BlobStore
    @Inject lateinit var handler: ResumableUploadHandler

    private val now = Instant.now()

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (users.findByEmail("gc-up@example.com") != null) return@run
            users.persist(User().apply { id = "gc-up-user"; email = "gc-up@example.com"; displayName = "U"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = "gc-up-scope"; ownerUserId = "gc-up-user"; kind = "PERSONAL_ROOT"; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "gc-up-mem"; scopeId = "gc-up-scope"; parentId = "gc-up-scope"; type = "MEMORY"; slugToken = "t"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            session("gc-stale", UploadStatus.OPEN, now.minus(48, ChronoUnit.HOURS))   // abandoned → purged
            session("gc-fresh", UploadStatus.OPEN, now)                                // still in progress → kept
            session("gc-done", UploadStatus.COMPLETED, now.minus(48, ChronoUnit.HOURS)) // finalized media → kept
        }
        // each session has one stored frame in the BlobStore
        blobs.put("gc-stale/0", byteArrayOf(1, 2, 3))
        blobs.put("gc-fresh/0", byteArrayOf(4, 5, 6))
        blobs.put("gc-done/0", byteArrayOf(7, 8, 9))
    }

    private fun session(id: String, status: UploadStatus, createdAt: Instant) {
        uploads.persist(UploadSession().apply {
            this.id = id; scopeId = "gc-up-scope"; nodeId = "gc-up-mem"; totalChunks = 1; mimeType = "video/mp4"
            byteSize = 3; takenAt = now; this.status = status.name; createdBy = "gc-up-user"; this.createdAt = createdAt
        })
        chunks.persist(MediaChunk().apply { this.id = "$id-c0"; uploadId = id; chunkIndex = 0; objectKey = "$id/0"; byteSize = 3; this.createdAt = now })
    }

    @Test
    fun purgesOnlyAbandonedOpenUploads() {
        val purged = handler.purgeStaleOpenUploads(now.minus(24, ChronoUnit.HOURS))

        assertEquals(1, purged, "only the stale OPEN upload is reclaimed")
        QuarkusTransaction.requiringNew().run {
            // the abandoned upload is gone: session, manifest rows and blob all reclaimed
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
