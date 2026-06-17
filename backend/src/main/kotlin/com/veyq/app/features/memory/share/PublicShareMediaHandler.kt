package com.veyq.app.features.memory.share

import com.veyq.app.generated.model.Blob as ApiBlob
import com.veyq.app.generated.model.ChunkResponse
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
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
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
    private val mapper: com.veyq.app.shared.content.MediaApiMapper,
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
        return MediaList(media = mapper.toApiBatch(media.listByNodeId(nodeId)))
    }

    fun getShareChunk(shareId: String, blobId: String, index: Int): ChunkResponse {
        // Resolve the chunk's metadata (share ACL + object key + suite) in a short transaction, then read the
        // bytes from the store OUTSIDE it — the DB connection isn't held across blob-store IO (the hot guest
        // download path).
        val (objectKey, suiteId) = QuarkusTransaction.requiringNew().call {
            val share = shareOr404(shareId)
            if (!ShareHandler.canRead(share.role)) throw ApiException(403, "SHARE_NO_READ", "This share cannot read content")
            if (!blobBacksSubtreeMedia(share, blobId)) throw ApiException(404, "BLOB_NOT_FOUND", "Blob not in this share")
            val chunk = chunks.findByBlob(blobId, index) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk not found")
            val suite = blobs.findById(blobId)?.suiteId ?: throw ApiException(404, "BLOB_NOT_FOUND", "Blob not in this share")
            chunk.objectKey to suite
        }
        val frame = store.get(objectKey) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk bytes missing")
        return ChunkResponse(frame = ApiBlob(suiteId = suiteId, bytes = enc.encodeToString(frame)))
    }

    private class AllowedBlobs(val ids: Set<String>, val expiresAtNanos: Long)
    private val allowedCache = java.util.concurrent.ConcurrentHashMap<String, AllowedBlobs>()
    private val allowedTtlNanos = 15_000_000_000L // 15s — a gallery's chunk burst reuses one computed set

    /**
     * Does [blobId] back a medium (original or derivative) in the share's subtree? The allowed-blob set is
     * cached per share for a few seconds so a gallery's many parallel chunk fetches don't each re-scan the
     * whole scope's nodes + media. A cache MISS for a blob (stale set, or a just-contributed medium) recomputes
     * and re-checks, so the answer is always correct — only disallowed-blob probes pay the recompute.
     */
    private fun blobBacksSubtreeMedia(share: com.veyq.app.shared.content.Share, blobId: String): Boolean {
        val now = System.nanoTime()
        allowedCache[share.id]?.let { if (now <= it.expiresAtNanos && blobId in it.ids) return true }
        val ids = computeAllowedBlobIds(share)
        allowedCache[share.id] = AllowedBlobs(ids, now + allowedTtlNanos)
        return blobId in ids
    }

    /** Drop expired allowed-blob sets so the cache can't grow unbounded across every share ever served. */
    @io.quarkus.scheduler.Scheduled(every = "5m")
    fun sweepAllowedCache() {
        val now = System.nanoTime()
        allowedCache.entries.removeIf { it.value.expiresAtNanos < now }
    }

    /** The set of blob ids (originals + derivatives) backing the share subtree — two IN queries. */
    private fun computeAllowedBlobIds(share: com.veyq.app.shared.content.Share): Set<String> {
        val nodeIds = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        val subtreeMedia = media.listByNodeIds(nodeIds)
        val ids = HashSet<String>()
        subtreeMedia.forEach { ids.add(it.originalBlobId) }
        derivatives.listByMediaIds(subtreeMedia.map { it.id }).forEach { ids.add(it.blobId) }
        return ids
    }
}
