package com.veyq.app.features.memory.share

import com.veyq.app.generated.model.Blob as ApiBlob
import com.veyq.app.generated.model.BlobRef
import com.veyq.app.generated.model.ChunkResponse
import com.veyq.app.generated.model.MediaDerivativeOut
import com.veyq.app.generated.model.MediaItem
import com.veyq.app.generated.model.MediaList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.BlobChunkRepository
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ShareRepository
import com.veyq.app.shared.storage.BlobStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * uc.memory.share — guest media (unauthenticated). Serves a shared node's media metadata and blob frames
 * (ciphertext), gated to the share's subtree: a guest can only fetch media of subtree nodes and the blobs
 * those media reference. The bytes stay sealed; the server is blind.
 */
@ApplicationScoped
class PublicShareMediaHandler(
    private val shares: ShareRepository,
    private val nodes: NodeRepository,
    private val media: MediaRepository,
    private val derivatives: MediaDerivativeRepository,
    private val blobs: BlobRepository,
    private val chunks: BlobChunkRepository,
    private val store: BlobStore,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun listShareMedia(shareId: String, nodeId: String): MediaList {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        if (nodeId !in ids) throw ApiException(404, "NODE_NOT_FOUND", "Node not in this share")
        return MediaList(media = media.listByNodeId(nodeId).map(::toApi))
    }

    @Transactional
    fun getShareChunk(shareId: String, blobId: String, index: Int): ChunkResponse {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        // The guest may only fetch blobs that back a medium (or its derivatives) in the shared subtree.
        val allowed = buildSet {
            for (nodeId in ids) for (m in media.listByNodeId(nodeId)) {
                add(m.originalBlobId)
                derivatives.listByMediaId(m.id).forEach { add(it.blobId) }
            }
        }
        if (blobId !in allowed) throw ApiException(404, "BLOB_NOT_FOUND", "Blob not in this share")
        val chunk = chunks.findByBlob(blobId, index) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk not found")
        val frame = store.get(chunk.objectKey) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk bytes missing")
        return ChunkResponse(frame = ApiBlob(suiteId = "v1", bytes = enc.encodeToString(frame)))
    }

    private fun toApi(m: Media): MediaItem =
        MediaItem(
            id = m.id, scopeId = m.scopeId, nodeId = m.nodeId,
            sealedMeta = ApiBlob(suiteId = m.suiteId, bytes = enc.encodeToString(m.sealedMeta)),
            mimeType = m.mimeType,
            original = blobRef(m.originalBlobId),
            derivatives = derivatives.listByMediaId(m.id).map { MediaDerivativeOut(kind = MediaDerivativeOut.Kind.valueOf(it.kind), blob = blobRef(it.blobId)) },
            createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
        )

    private fun blobRef(blobId: String): BlobRef {
        val b = blobs.findById(blobId) ?: throw ApiException(500, "MISSING_BLOB", "Blob not found")
        return BlobRef(blobId = b.id, totalChunks = b.totalChunks, byteSize = b.byteSize)
    }
}
