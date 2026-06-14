package com.veyq.app.features.uploads.addmedia

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.CreateMediaRequest
import com.veyq.app.generated.model.MediaItem
import com.veyq.app.generated.model.MediaList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.Media
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.uploads.add-media — attach encrypted media to a Memory/Chapter node. The bytes + light thumbnail are
 * sealed under the scope content key (client-side); the server stores ciphertext and orders the gallery by
 * takenAt. Media live only inside a Memory or Chapter (never a Folder). Dedup, buckets and streaming
 * derivatives are later slices.
 */
@ApplicationScoped
class AddMediaHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val nodes: NodeRepository,
    private val media: MediaRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val memberships: MembershipRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun listMedia(auth: String?, scopeId: String, nodeId: String): MediaList {
        requireKeyHolder(scopeId, auth)
        requireMemoryNode(scopeId, nodeId)
        return MediaList(media = media.listByNodeId(nodeId).map(::toApi))
    }

    @Transactional
    fun addMedia(auth: String?, scopeId: String, nodeId: String, req: CreateMediaRequest): MediaItem {
        requireKeyHolder(scopeId, auth)
        requireMemoryNode(scopeId, nodeId)
        contentKeys.findByScopeId(scopeId) ?: throw ApiException(409, "CONTENT_KEY_REQUIRED", "Provision the scope content key before adding media")
        if (!suites.isKnown(req.sealedBlob.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        if (req.byteSize < 0) throw ApiException(400, "INVALID_SIZE", "byteSize must be non-negative")

        val item = Media().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            takenAt = req.takenAt.toInstant(); mimeType = req.mimeType; byteSize = req.byteSize
            suiteId = req.sealedBlob.suiteId; sealedBlob = dec.decode(req.sealedBlob.bytes)
            sealedThumb = req.sealedThumb?.let { dec.decode(it.bytes) }; createdAt = Instant.now()
        }
        media.persist(item)
        return toApi(item)
    }

    private fun toApi(m: Media): MediaItem =
        MediaItem(
            id = m.id, scopeId = m.scopeId, nodeId = m.nodeId,
            takenAt = OffsetDateTime.ofInstant(m.takenAt, ZoneOffset.UTC),
            mimeType = m.mimeType, byteSize = m.byteSize,
            sealedBlob = Blob(suiteId = m.suiteId, bytes = enc.encodeToString(m.sealedBlob)),
            sealedThumb = m.sealedThumb?.let { Blob(suiteId = m.suiteId, bytes = enc.encodeToString(it)) },
            createdAt = OffsetDateTime.ofInstant(m.createdAt, ZoneOffset.UTC),
        )

    /** Media hang off a Memory or Chapter (a content unit), never a Folder, and only within this scope. */
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
