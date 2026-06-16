package com.veyq.app.features.uploads.media

import com.veyq.app.generated.model.BlobRef
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
        requireKeyHolder(scopeId, auth)
        return createMediaAs(scopeId, nodeId, req)
    }

    /** Attach media for an already-authorized writer (a session key holder or a CONTRIBUTOR share). */
    @Transactional
    fun createMediaAs(scopeId: String, nodeId: String, req: CreateMediaRequest): MediaItem {
        requireMemoryNode(scopeId, nodeId)
        requireBlob(scopeId, req.originalBlobId)
        if (!suites.isKnown(req.sealedMeta.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")

        val item = Media().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            originalBlobId = req.originalBlobId
            sealedMeta = dec.decode(req.sealedMeta.bytes); suiteId = req.sealedMeta.suiteId
            mimeType = req.mimeType; createdAt = Instant.now()
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
            createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
        )

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
        if (node.type != "MEMORY" && node.type != "CHAPTER") {
            throw ApiException(400, "NOT_A_MEMORY", "Media can only be attached to a Memory or Chapter")
        }
    }

    private fun requireKeyHolder(scopeId: String, auth: String?) {
        guard.requireKeyHolder(auth, scopeId)
    }
}
