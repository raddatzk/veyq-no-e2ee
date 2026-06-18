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
import com.veyq.app.shared.content.MediaBucket
import com.veyq.app.shared.content.MediaBucketRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.Share
import com.veyq.app.shared.content.ShareRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.memory.share — guest contribution (unauthenticated, CONTRIBUTOR/UPLOADER shares only). A guest holding
 * a writable link uploads media through the same deduplicated blob path as a member (gated to the share's
 * scope) and attaches it to a node in the shared subtree. Authorization is the share role plus a blind upload
 * capability derived client-side from the share unwrap key; the server stores only its SHA-256. The guest
 * already holds the content key (from the link fragment), so the bytes stay sealed end-to-end.
 */
@ApplicationScoped
class PublicShareContributeHandler(
    private val shares: ShareRepository,
    private val nodes: NodeRepository,
    private val blobUploads: BlobUploadHandler,
    private val media: MediaHandler,
    private val buckets: MediaBucketRepository,
) {
    private val dec = Base64.getUrlDecoder()
    // Every guest upload op is bound to THIS share's owner tag, so a guest who learns another guest's uploadId
    // (a second share on the same scope, or a co-guest of one CONTRIBUTOR link) can't read/overwrite/finalize it.
    fun startShareBlob(shareId: String, uploadToken: String?, req: StartBlobRequest): BlobTicket =
        uploader(shareId, uploadToken).let { blobUploads.startBlobAs(it.scopeId, "share:${it.id}", req) }

    fun putShareChunk(shareId: String, uploadId: String, index: Int, uploadToken: String?, req: PutChunkRequest) =
        uploader(shareId, uploadToken).let { blobUploads.putBlobChunkAs(it.scopeId, uploadId, index, req, "share:${it.id}") }

    fun getShareUpload(shareId: String, uploadId: String, uploadToken: String?): BlobUploadProgress =
        uploader(shareId, uploadToken).let { blobUploads.getBlobUploadAs(it.scopeId, uploadId, "share:${it.id}") }

    fun completeShareBlob(shareId: String, uploadId: String, uploadToken: String?): CompleteBlobResponse =
        uploader(shareId, uploadToken).let { blobUploads.completeBlobUploadAs(it.scopeId, uploadId, "share:${it.id}") }

    fun createShareMedia(shareId: String, nodeId: String, uploadToken: String?, req: CreateMediaRequest): MediaItem {
        val share = uploader(shareId, uploadToken)
        val ids = ShareHandler.subtreeIds(nodes.listByScopeId(share.scopeId), share.nodeId)
        if (nodeId !in ids) throw ApiException(404, "NODE_NOT_FOUND", "Node not in this share")
        // A guest may only attach blobs THEY uploaded via this share — not arbitrary scope blobs whose id they
        // happen to know. (createMediaAs only checks the blob is in-scope, which is too permissive for guests.)
        // Blob ownership is gated per-SHARE (the upload tag); media attribution is per-GUEST when the guest
        // named themselves (ADR 0023) — so co-guests of one link still get distinct contribution buckets.
        val owner = "share:${share.id}"
        requireFromThisShare(share.scopeId, req.originalBlobId, owner)
        req.derivatives.orEmpty().forEach { requireFromThisShare(share.scopeId, it.blobId, owner) }
        val contributorKey = req.contributorId?.takeIf { it.isNotBlank() }?.let { "share:${share.id}:$it" } ?: owner
        req.contributorName?.let { ensureGuestPrimary(share.scopeId, nodeId, contributorKey, it) }
        return media.createMediaAs(share.scopeId, nodeId, req, contributorKey)
    }

    /** Persist a guest's named primary contribution bucket on first contribution (idempotent). The name is
     *  sealed by the guest under the share content key — the server stores ciphertext only. */
    @Transactional
    fun ensureGuestPrimary(scopeId: String, nodeId: String, ownerKey: String, sealedName: com.veyq.app.generated.model.Blob) {
        if (buckets.findPrimary(nodeId, ownerKey) != null) return
        buckets.persist(MediaBucket().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            this.ownerKey = ownerKey; parentBucketId = null
            this.sealedName = dec.decode(sealedName.bytes); suiteId = sealedName.suiteId
            isPrimary = true; createdAt = Instant.now()
        })
    }

    private fun requireFromThisShare(scopeId: String, blobId: String, owner: String) {
        if (!blobUploads.wasUploadedBy(scopeId, blobId, owner)) {
            throw ApiException(403, "BLOB_NOT_FROM_SHARE", "A contribution may only reference blobs uploaded through this share")
        }
    }

    /** Resolve a non-expired share and require an upload permission (CONTRIBUTOR or the blind-drop UPLOADER). */
    private fun uploader(shareId: String, uploadToken: String?): Share {
        val share = shares.findById(shareId) ?: throw ApiException(404, "SHARE_NOT_FOUND", "Share not found")
        if (ShareHandler.isExpired(share)) throw ApiException(404, "SHARE_EXPIRED", "This share has expired")
        if (!ShareHandler.canUpload(share.role)) throw ApiException(403, "SHARE_READ_ONLY", "This share cannot upload")
        requireUploadProof(share, uploadToken)
        return share
    }

    private fun requireUploadProof(share: Share, uploadToken: String?) {
        val expected = share.uploadCapabilityHash
            ?: throw ApiException(403, "SHARE_UPLOAD_PROOF_REQUIRED", "This share requires a fresh upload capability")
        val raw = try {
            dec.decode(uploadToken?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing"))
        } catch (_: IllegalArgumentException) {
            throw ApiException(403, "SHARE_UPLOAD_PROOF_INVALID", "Invalid share upload capability")
        }
        if (!MessageDigest.isEqual(sha256(raw), expected)) {
            throw ApiException(403, "SHARE_UPLOAD_PROOF_INVALID", "Invalid share upload capability")
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
