package com.veyq.app.features.memory.share

import com.veyq.app.features.uploads.blob.BlobUploadHandler
import com.veyq.app.generated.model.BatchPresignGetItem
import com.veyq.app.generated.model.BatchPresignGetRequest
import com.veyq.app.generated.model.BatchPresignGetResponse
import com.veyq.app.generated.model.MediaList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ShareRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

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
    private val mapper: com.veyq.app.shared.content.MediaApiMapper,
    private val blobUploads: BlobUploadHandler,
) {

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

    /**
     * Batched presigned GET URLs for many shared blobs (direct-from-store download, ADR 0021) — the guest
     * gallery's fast path. Each blob is gated to the share's subtree (same ACL as getShareChunk); a blob the
     * guest may not read (or that can't be presigned) is simply omitted from the results.
     */
    fun batchPresignShareGet(shareId: String, req: BatchPresignGetRequest): BatchPresignGetResponse {
        val share = QuarkusTransaction.requiringNew().call {
            val s = shareOr404(shareId)
            if (!ShareHandler.canRead(s.role)) throw ApiException(403, "SHARE_NO_READ", "This share cannot read content")
            s
        }
        val results = req.blobIds.distinct().mapNotNull { blobId ->
            try {
                QuarkusTransaction.requiringNew().call {
                    if (!blobBacksSubtreeMedia(share, blobId)) return@call null
                    val pc = blobUploads.getBlobPresignedChunksAs(share.scopeId, blobId)
                    BatchPresignGetItem(blobId = blobId, suiteId = pc.suiteId, chunks = pc.chunks)
                }
            } catch (_: ApiException) {
                null
            }
        }
        return BatchPresignGetResponse(results = results)
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
