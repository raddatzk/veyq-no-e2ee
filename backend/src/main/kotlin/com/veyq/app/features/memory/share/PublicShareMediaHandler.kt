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

    private fun shareOr404(shareId: String): com.veyq.app.shared.content.Share {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        if (ShareHandler.isExpired(share)) throw ApiException(404, "SHARE_EXPIRED", "This share has expired")
        return share
    }

    @Transactional
    fun listShareMedia(shareId: String, nodeId: String): MediaList {
        val share = shareOr404(shareId)
        if (!ShareHandler.canRead(share.role)) throw ApiException(403, "SHARE_NO_READ", "This share cannot read content")
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        if (nodeId !in ids) throw ApiException(404, "NODE_NOT_FOUND", "Node not in this share")
        return MediaList(media = toApiBatch(media.listByNodeId(nodeId)))
    }

    @Transactional
    fun getShareChunk(shareId: String, blobId: String, index: Int): ChunkResponse {
        val share = shareOr404(shareId)
        if (!ShareHandler.canRead(share.role)) throw ApiException(403, "SHARE_NO_READ", "This share cannot read content")
        if (!blobBacksSubtreeMedia(share, blobId)) throw ApiException(404, "BLOB_NOT_FOUND", "Blob not in this share")
        val chunk = chunks.findByBlob(blobId, index) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk not found")
        val frame = store.get(chunk.objectKey) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk bytes missing")
        return ChunkResponse(frame = ApiBlob(suiteId = "v1", bytes = enc.encodeToString(frame)))
    }

    /** Does [blobId] back a medium (original or derivative) in the share's subtree? At most two IN queries
     *  (subtree media, then their derivatives), vs the old per-node × per-media loop on every chunk fetch. */
    private fun blobBacksSubtreeMedia(share: com.veyq.app.shared.content.Share, blobId: String): Boolean {
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        val subtreeMedia = media.listByNodeIds(ids)
        if (subtreeMedia.any { it.originalBlobId == blobId }) return true
        return derivatives.listByMediaIds(subtreeMedia.map { it.id }).any { it.blobId == blobId }
    }

    /** Map a share gallery in a fixed 3 queries (media + derivatives IN + blobs IN), not N+1. */
    private fun toApiBatch(items: List<Media>): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        val derivsByMedia = derivatives.listByMediaIds(items.map { it.id }).groupBy { it.mediaId }
        val blobsById = blobs.listByIds((items.map { it.originalBlobId } + derivsByMedia.values.flatten().map { it.blobId }).toSet()).associateBy { it.id }
        fun ref(blobId: String): BlobRef {
            val b = blobsById[blobId] ?: throw ApiException(500, "MISSING_BLOB", "Blob not found")
            return BlobRef(blobId = b.id, totalChunks = b.totalChunks, byteSize = b.byteSize)
        }
        return items.map { m ->
            MediaItem(
                id = m.id, scopeId = m.scopeId, nodeId = m.nodeId,
                sealedMeta = ApiBlob(suiteId = m.suiteId, bytes = enc.encodeToString(m.sealedMeta)),
                mimeType = m.mimeType,
                original = ref(m.originalBlobId),
                derivatives = derivsByMedia[m.id].orEmpty().map { MediaDerivativeOut(kind = MediaDerivativeOut.Kind.valueOf(it.kind), blob = ref(it.blobId)) },
                createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
            )
        }
    }
}
