package com.veyq.app.features.uploads.media

import com.veyq.app.generated.model.BlobRef
import com.veyq.app.generated.model.NodeType
import com.veyq.app.generated.model.CreateMediaRequest
import com.veyq.app.generated.model.MediaDerivativeOut
import com.veyq.app.generated.model.MediaItem
import com.veyq.app.generated.model.MediaList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.DerivativeKind
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaDerivative
import com.veyq.app.shared.content.MediaApiMapper
import com.veyq.app.shared.content.MediaBucketRepository
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.security.ScopeAccessGuard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.uploads (media attachment) — hang a stored content blob on a Memory/Chapter, with typed client-generated
 * derivatives (thumbnail/preview, themselves blobs). The gallery lists all media uniformly (in upload order;
 * the client sorts by decrypted takenAt); inline vs. chunked no longer exists — every medium is one blob.
 */
@ApplicationScoped
class MediaHandler(
    private val nodes: NodeRepository,
    private val blobs: BlobRepository,
    private val media: MediaRepository,
    private val buckets: MediaBucketRepository,
    private val derivatives: MediaDerivativeRepository,
    private val mapper: MediaApiMapper,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    @Transactional
    fun listMedia(auth: String?, scopeId: String, nodeId: String): MediaList {
        requireKeyHolder(scopeId, auth)
        requireMemoryNode(scopeId, nodeId)
        return MediaList(media = mapper.toApiBatch(media.listByNodeId(nodeId)))
    }

    @Transactional
    fun createMedia(auth: String?, scopeId: String, nodeId: String, req: CreateMediaRequest): MediaItem {
        val uid = requireKeyHolder(scopeId, auth)
        return createMediaAs(scopeId, nodeId, req, "user:$uid")
    }

    /** Attach media for an already-authorized writer (a session key holder or a CONTRIBUTOR share). The
     * [createdBy] contributor key ("user:<id>" / "share:<shareId>:<contributorId>") attributes the medium to a
     * contribution bucket (ADR 0023); an explicit req.bucketId must be one of that contributor's OWN buckets. */
    @Transactional
    fun createMediaAs(scopeId: String, nodeId: String, req: CreateMediaRequest, createdBy: String): MediaItem {
        requireMemoryNode(scopeId, nodeId)
        requireBlob(scopeId, req.originalBlobId)
        if (!suites.isKnown(req.sealedMeta.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        val bucketId = resolveOwnBucket(nodeId, req.bucketId, createdBy)

        val item = Media().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            originalBlobId = req.originalBlobId
            sealedMeta = dec.decode(req.sealedMeta.bytes); suiteId = req.sealedMeta.suiteId
            mimeType = req.mimeType; this.createdBy = createdBy; this.bucketId = bucketId; createdAt = Instant.now()
        }
        media.persist(item)
        req.derivatives.orEmpty().forEach { d ->
            requireBlob(scopeId, d.blobId)
            DerivativeKind.valueOf(d.kind.value) // reject unknown kinds
            derivatives.persist(MediaDerivative().apply {
                id = UUID.randomUUID().toString(); mediaId = item.id; kind = d.kind.value; blobId = d.blobId
            })
        }
        return toApi(item)
    }

    private fun toApi(m: Media): MediaItem =
        MediaItem(
            id = m.id, scopeId = m.scopeId, nodeId = m.nodeId,
            sealedMeta = com.veyq.app.generated.model.Blob(suiteId = m.suiteId, bytes = enc.encodeToString(m.sealedMeta)),
            mimeType = m.mimeType,
            original = blobRef(m.originalBlobId),
            derivatives = derivatives.listByMediaId(m.id).map {
                MediaDerivativeOut(kind = MediaDerivativeOut.Kind.valueOf(it.kind), blob = blobRef(it.blobId))
            },
            highlighted = m.highlighted,
            createdBy = m.createdBy,
            bucketId = m.bucketId,
            createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
        )

    /** Validate that an explicit target [bucketId] is one of [createdBy]'s OWN buckets in [nodeId]; null/blank
     * or a synthetic "primary:<key>" id means the contributor's primary bucket (stored as null). 400/403 else. */
    private fun resolveOwnBucket(nodeId: String, bucketId: String?, createdBy: String): String? {
        val raw = bucketId?.trim().orEmpty()
        if (raw.isEmpty() || raw == "primary:$createdBy") return null
        if (raw.startsWith("primary:")) throw ApiException(403, "NOT_YOUR_BUCKET", "Not your bucket")
        val b = buckets.findById(raw) ?: throw ApiException(404, "BUCKET_NOT_FOUND", "Bucket not found")
        if (b.nodeId != nodeId) throw ApiException(404, "BUCKET_NOT_FOUND", "Bucket not in this node")
        if (b.ownerKey != createdBy) throw ApiException(403, "NOT_YOUR_BUCKET", "You can only use your own buckets")
        return b.id
    }

    /** Toggle the highlight flag and/or replace the (re-sealed) metadata of a medium — any scope key holder. */
    @Transactional
    fun updateMedia(auth: String?, scopeId: String, nodeId: String, mediaId: String, req: com.veyq.app.generated.model.UpdateMediaRequest): MediaItem {
        val uid = requireKeyHolder(scopeId, auth)
        val item = media.findById(mediaId) ?: throw ApiException(404, "MEDIA_NOT_FOUND", "Media not found")
        if (item.scopeId != scopeId || item.nodeId != nodeId) throw ApiException(404, "MEDIA_NOT_FOUND", "Media not found here")
        req.highlighted?.let { item.highlighted = it } // benign favourite — any key holder
        req.sealedMeta?.let { // capture-date / location re-seal — any key holder
            if (!suites.isKnown(it.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
            item.sealedMeta = dec.decode(it.bytes); item.suiteId = it.suiteId
        }
        // Move between the CALLER'S OWN buckets only: the medium must be theirs and the target their own bucket.
        req.bucketId?.let { target ->
            val callerKey = "user:$uid"
            if (item.createdBy != callerKey) throw ApiException(403, "NOT_YOUR_MEDIA", "You can only move your own media")
            item.bucketId = resolveOwnBucket(nodeId, target, callerKey)
        }
        return toApi(item)
    }

    /** Remove a single medium and its derivatives; un-pin it as a cover wherever it was used. */
    @Transactional
    fun deleteMedia(auth: String?, scopeId: String, nodeId: String, mediaId: String) {
        requireKeyHolder(scopeId, auth)
        val item = media.findById(mediaId) ?: throw ApiException(404, "MEDIA_NOT_FOUND", "Media not found")
        if (item.scopeId != scopeId || item.nodeId != nodeId) throw ApiException(404, "MEDIA_NOT_FOUND", "Media not found here")
        derivatives.deleteByMediaIds(listOf(mediaId))
        nodes.clearCoverByMediaIds(listOf(mediaId))
        media.delete(item)
    }

    private fun blobRef(blobId: String): BlobRef {
        val b = blobs.findById(blobId) ?: throw ApiException(500, "MISSING_BLOB", "Blob not found")
        return BlobRef(blobId = b.id, totalChunks = b.totalChunks, byteSize = b.byteSize)
    }

    private fun requireBlob(scopeId: String, blobId: String): Blob {
        val b = blobs.findById(blobId) ?: throw ApiException(404, "BLOB_NOT_FOUND", "Blob not found")
        if (b.scopeId != scopeId) throw ApiException(404, "BLOB_NOT_FOUND", "Blob not found in this scope")
        return b
    }

    private fun requireMemoryNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        val type = node.type // typed compare instead of raw strings (consistent with siblings)
        if (type != NodeType.MEMORY && type != NodeType.CHAPTER) {
            throw ApiException(400, "NOT_A_MEMORY", "Media can only be attached to a Memory or Chapter")
        }
    }

    private fun requireKeyHolder(scopeId: String, auth: String?): String =
        guard.requireKeyHolder(auth, scopeId)
}
