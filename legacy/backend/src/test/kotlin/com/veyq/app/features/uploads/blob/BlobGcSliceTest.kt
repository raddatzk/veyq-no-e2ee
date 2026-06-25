package com.veyq.app.features.uploads.blob

import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobChunk
import com.veyq.app.shared.content.BlobChunkRepository
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaDerivative
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.Node
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.generated.model.NodeType
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeKind
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

/**
 * uc.uploads (blob GC, ADR 0007) — finalized blobs no media references anymore are reclaimed (frames + chunk
 * rows + blob row), but only after a grace cutoff; referenced and still-fresh blobs survive. Drives the
 * handler directly (the @Scheduled sweep just calls it).
 */
@QuarkusTest
class BlobGcSliceTest {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var scopes: ScopeRepository
    @Inject lateinit var blobs: BlobRepository
    @Inject lateinit var chunks: BlobChunkRepository
    @Inject lateinit var media: MediaRepository
    @Inject lateinit var derivatives: MediaDerivativeRepository
    @Inject lateinit var nodes: NodeRepository
    @Inject lateinit var store: BlobStore
    @Inject lateinit var handler: BlobUploadHandler

    private val scopeId = "gc-scope"
    private fun key(blobId: String) = "gc/$blobId/0"

    @BeforeTest
    fun seed() {
        QuarkusTransaction.requiringNew().run {
            if (scopes.findById(scopeId) != null) return@run
            val old = Instant.now().minus(2, ChronoUnit.HOURS)
            val now = Instant.now()
            users.persist(User().apply { id = "gc-owner"; email = "gc-owner@example.com"; displayName = "GC"; serverRole = ServerRole.ServerMember; createdAt = now })
            scopes.persist(Scope().apply { id = scopeId; ownerUserId = "gc-owner"; kind = ScopeKind.PERSONAL_ROOT; suiteId = "v1"; kemPublicKey = "p".toByteArray(); createdAt = now })
            nodes.persist(Node().apply { id = "gc-node"; this.scopeId = this@BlobGcSliceTest.scopeId; parentId = this@BlobGcSliceTest.scopeId; type = NodeType.MEMORY; slugToken = "gc-n"; orderIndex = 0; suiteId = "v1"; sealedContent = "n".toByteArray(); createdAt = now })
            // four blobs, each with one stored frame
            fun blob(id: String, createdAt: Instant) {
                blobs.persist(Blob().apply { this.id = id; this.scopeId = this@BlobGcSliceTest.scopeId; dedupToken = id; totalChunks = 1; byteSize = 3; suiteId = "v1"; this.createdAt = createdAt })
                chunks.persist(BlobChunk().apply { this.id = "$id-c"; blobId = id; chunkIndex = 0; objectKey = key(id); byteSize = 3; this.createdAt = createdAt })
                store.put(key(id), byteArrayOf(1, 2, 3))
            }
            blob("gc-ref", old)       // referenced by a media original → keep
            blob("gc-deriv", old)     // referenced by a derivative → keep
            blob("gc-orphan-old", old) // unreferenced + old → reap
            blob("gc-orphan-new", now) // unreferenced but within grace → keep
            // a media referencing gc-ref, with a derivative referencing gc-deriv
            media.persist(Media().apply { id = "gc-med"; this.scopeId = this@BlobGcSliceTest.scopeId; nodeId = "gc-node"; originalBlobId = "gc-ref"; sealedMeta = "m".toByteArray(); suiteId = "v1"; mimeType = "image/png"; createdAt = now })
            derivatives.persist(MediaDerivative().apply { id = "gc-d"; mediaId = "gc-med"; kind = "THUMBNAIL"; blobId = "gc-deriv" })
        }
    }

    @Test
    fun reclaimsOnlyUnreferencedBlobsPastTheGrace() {
        val cutoff = Instant.now().minus(1, ChronoUnit.HOURS)
        val removed = handler.purgeUnreferencedBlobs(cutoff)
        assertEquals(1, removed)

        QuarkusTransaction.requiringNew().run {
            // reaped: row + chunk rows + stored frame all gone
            assertNull(blobs.findById("gc-orphan-old"), "old orphan blob should be reaped")
            assertEquals(0, chunks.listByBlobId("gc-orphan-old").size)
            assertNull(store.get(key("gc-orphan-old")), "old orphan frame should be deleted from the store")
            // survivors
            assertNotNull(blobs.findById("gc-ref"), "media-referenced blob must survive")
            assertNotNull(blobs.findById("gc-deriv"), "derivative-referenced blob must survive")
            assertNotNull(blobs.findById("gc-orphan-new"), "fresh orphan within grace must survive")
            assertNotNull(store.get(key("gc-ref")), "referenced frame must survive")
        }
    }
}
