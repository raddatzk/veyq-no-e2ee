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
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * uc.uploads (media attachment) — hang a stored content blob on a Memory/Chapter, with typed client-generated
 * derivatives (thumbnail/preview, themselves blobs). The gallery lists all media uniformly (ordered by
 * takenAt); inline vs. chunked no longer exists — every medium is one blob (1 chunk or many).
 */
@ApplicationScoped
class MediaHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val nodes: NodeRepository,
    private val blobs: BlobRepository,
    private val media: MediaRepository,
    private val derivatives: MediaDerivativeRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
) {
    @Transactional
    fun listMedia(auth: String?, scopeId: String, nodeId: String): MediaList {
        requireKeyHolder(scopeId, auth)
        requireMemoryNode(scopeId, nodeId)
        return MediaList(media = media.listByNodeId(nodeId).map(::toApi))
    }

    @Transactional
    fun createMedia(auth: String?, scopeId: String, nodeId: String, req: CreateMediaRequest): MediaItem {
        requireKeyHolder(scopeId, auth)
        requireMemoryNode(scopeId, nodeId)
        requireBlob(scopeId, req.originalBlobId)

        val item = Media().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            originalBlobId = req.originalBlobId; takenAt = req.takenAt.toInstant(); mimeType = req.mimeType; createdAt = Instant.now()
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
            takenAt = OffsetDateTime.ofInstant(m.takenAt, ZoneOffset.UTC),
            mimeType = m.mimeType,
            original = blobRef(m.originalBlobId),
            derivatives = derivatives.listByMediaId(m.id).map {
                MediaDerivativeOut(kind = MediaDerivativeOut.Kind.valueOf(it.kind), blob = blobRef(it.blobId))
            },
            createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
        )

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
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "NOT_FOUND", "Scope not found")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        val tenantId = scope.tenantId
        if (tenantId != null && memberships.find(tenantId, userId)?.status != "ACTIVE") {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
    }
}
