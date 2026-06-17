package com.veyq.app.features.memory.share

import com.veyq.app.features.uploads.blob.BlobUploadHandler
import com.veyq.app.features.uploads.media.MediaHandler
import com.veyq.app.generated.model.BlobTicket
import com.veyq.app.generated.model.BlobUploadProgress
import com.veyq.app.generated.model.CompleteBlobResponse
import com.veyq.app.generated.model.CreateMediaRequest
import com.veyq.app.generated.model.MediaItem
import com.veyq.app.generated.model.PutChunkRequest
import com.veyq.app.generated.model.StartBlobRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.Share
import com.veyq.app.shared.content.ShareRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * uc.memory.share — guest contribution (unauthenticated, CONTRIBUTOR shares only). A guest holding a
 * CONTRIBUTOR link uploads media through the same deduplicated blob path as a member (gated to the share's
 * scope) and attaches it to a node in the shared subtree. Authorization is the share role, not a session;
 * the guest already holds the content key (from the link fragment), so the bytes stay sealed end-to-end.
 */
@ApplicationScoped
class PublicShareContributeHandler(
    private val shares: ShareRepository,
    private val nodes: NodeRepository,
    private val blobUploads: BlobUploadHandler,
    private val media: MediaHandler,
) {
    // Every guest upload op is bound to THIS share's owner tag, so a guest who learns another guest's uploadId
    // (a second share on the same scope, or a co-guest of one CONTRIBUTOR link) can't read/overwrite/finalize it.
    fun startShareBlob(shareId: String, req: StartBlobRequest): BlobTicket =
        uploader(shareId).let { blobUploads.startBlobAs(it.scopeId, "share:${it.id}", req) }

    fun putShareChunk(shareId: String, uploadId: String, index: Int, req: PutChunkRequest) =
        uploader(shareId).let { blobUploads.putBlobChunkAs(it.scopeId, uploadId, index, req, "share:${it.id}") }

    fun getShareUpload(shareId: String, uploadId: String): BlobUploadProgress =
        uploader(shareId).let { blobUploads.getBlobUploadAs(it.scopeId, uploadId, "share:${it.id}") }

    fun completeShareBlob(shareId: String, uploadId: String): CompleteBlobResponse =
        uploader(shareId).let { blobUploads.completeBlobUploadAs(it.scopeId, uploadId, "share:${it.id}") }

    fun createShareMedia(shareId: String, nodeId: String, req: CreateMediaRequest): MediaItem {
        val share = uploader(shareId)
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        if (nodeId !in ids) throw ApiException(404, "NODE_NOT_FOUND", "Node not in this share")
        // A guest may only attach blobs THEY uploaded via this share — not arbitrary scope blobs whose id they
        // happen to know. (createMediaAs only checks the blob is in-scope, which is too permissive for guests.)
        val owner = "share:${share.id}"
        requireFromThisShare(share.scopeId, req.originalBlobId, owner)
        req.derivatives.orEmpty().forEach { requireFromThisShare(share.scopeId, it.blobId, owner) }
        return media.createMediaAs(share.scopeId, nodeId, req)
    }

    private fun requireFromThisShare(scopeId: String, blobId: String, owner: String) {
        if (!blobUploads.wasUploadedBy(scopeId, blobId, owner)) {
            throw ApiException(403, "BLOB_NOT_FROM_SHARE", "A contribution may only reference blobs uploaded through this share")
        }
    }

    /** Resolve a non-expired share and require an upload permission (CONTRIBUTOR or the blind-drop UPLOADER). */
    private fun uploader(shareId: String): Share {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        if (ShareHandler.isExpired(share)) throw ApiException(404, "SHARE_EXPIRED", "This share has expired")
        if (!ShareHandler.canUpload(share.role)) throw ApiException(403, "SHARE_READ_ONLY", "This share cannot upload")
        return share
    }
}
