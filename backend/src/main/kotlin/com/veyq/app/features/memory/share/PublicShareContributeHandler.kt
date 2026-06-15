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
    fun startShareBlob(shareId: String, req: StartBlobRequest): BlobTicket =
        uploader(shareId).let { blobUploads.startBlobAs(it.scopeId, "share:${it.id}", req) }

    fun putShareChunk(shareId: String, uploadId: String, index: Int, req: PutChunkRequest) =
        blobUploads.putBlobChunkAs(uploader(shareId).scopeId, uploadId, index, req)

    fun getShareUpload(shareId: String, uploadId: String): BlobUploadProgress =
        blobUploads.getBlobUploadAs(uploader(shareId).scopeId, uploadId)

    fun completeShareBlob(shareId: String, uploadId: String): CompleteBlobResponse =
        blobUploads.completeBlobUploadAs(uploader(shareId).scopeId, uploadId)

    fun createShareMedia(shareId: String, nodeId: String, req: CreateMediaRequest): MediaItem {
        val share = uploader(shareId)
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        if (nodeId !in ids) throw ApiException(404, "NODE_NOT_FOUND", "Node not in this share")
        return media.createMediaAs(share.scopeId, nodeId, req)
    }

    /** Resolve a share and require an upload permission (CONTRIBUTOR or the blind-drop UPLOADER). */
    private fun uploader(shareId: String): Share {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        if (!ShareHandler.canUpload(share.role)) throw ApiException(403, "SHARE_READ_ONLY", "This share cannot upload")
        return share
    }
}
