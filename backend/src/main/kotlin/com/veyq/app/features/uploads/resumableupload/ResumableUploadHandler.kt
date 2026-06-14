package com.veyq.app.features.uploads.resumableupload

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.ChunkResponse
import com.veyq.app.generated.model.CreateUploadRequest
import com.veyq.app.generated.model.PutChunkRequest
import com.veyq.app.generated.model.UploadProgress
import com.veyq.app.generated.model.UploadSession as ApiUploadSession
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.MediaChunk
import com.veyq.app.shared.content.MediaChunkRepository
import com.veyq.app.shared.content.NodeRepository
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.content.UploadSession
import com.veyq.app.shared.content.UploadSessionRepository
import com.veyq.app.shared.content.UploadStatus
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.storage.BlobStore
import com.veyq.app.shared.tenancy.MembershipRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.uploads.resumable-upload — chunked, resumable media upload. A large media is split into independent
 * AEAD frames; the ciphertext frames go to the BlobStore (not the DB) and the manifest tracks which indices
 * landed. The session survives interruptions: the client asks which chunks exist and re-sends only the
 * missing ones, then finalizes. The server only ever moves opaque frames — it is blind to the media.
 */
@ApplicationScoped
class ResumableUploadHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val nodes: NodeRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val sessions: UploadSessionRepository,
    private val chunks: MediaChunkRepository,
    private val blobs: BlobStore,
    private val memberships: MembershipRepository,
    private val auth: SessionService,
    @param:ConfigProperty(name = "veyq.uploads.max-chunks", defaultValue = "10000") private val maxChunks: Int,
    @param:ConfigProperty(name = "veyq.uploads.max-upload-bytes", defaultValue = "10737418240") private val maxUploadBytes: Long,
    @param:ConfigProperty(name = "veyq.uploads.max-frame-bytes", defaultValue = "16777244") private val maxFrameBytes: Int,
    @param:ConfigProperty(name = "veyq.uploads.max-open-sessions", defaultValue = "100") private val maxOpenSessions: Long,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val maxEncodedFrameChars: Int = ((maxFrameBytes + 2) / 3) * 4

    @Transactional
    fun createUpload(authz: String?, scopeId: String, nodeId: String, req: CreateUploadRequest): ApiUploadSession {
        val userId = requireKeyHolder(scopeId, authz)
        requireMemoryNode(scopeId, nodeId)
        contentKeys.findByScopeId(scopeId) ?: throw ApiException(409, "CONTENT_KEY_REQUIRED", "Provision the scope content key before uploading")
        if (req.totalChunks <= 0) throw ApiException(400, "INVALID_CHUNK_COUNT", "totalChunks must be positive")
        if (req.totalChunks > maxChunks) throw ApiException(400, "UPLOAD_TOO_MANY_CHUNKS", "totalChunks exceeds the configured upload limit")
        if (req.byteSize < 0) throw ApiException(400, "INVALID_SIZE", "byteSize must be non-negative")
        if (req.byteSize > maxUploadBytes) throw ApiException(400, "UPLOAD_TOO_LARGE", "byteSize exceeds the configured upload limit")
        // cap concurrent OPEN sessions per scope so a member can't spam empty sessions to exhaust DB rows
        // (each costs nothing to create until the abandoned-upload GC reclaims it).
        if (sessions.countOpenByScope(scopeId) >= maxOpenSessions) {
            throw ApiException(429, "TOO_MANY_OPEN_UPLOADS", "Too many in-progress uploads — finish or abandon some first")
        }
        val s = UploadSession().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; this.nodeId = nodeId
            totalChunks = req.totalChunks; mimeType = req.mimeType; byteSize = req.byteSize
            takenAt = req.takenAt.toInstant(); status = UploadStatus.OPEN.name; createdBy = userId; createdAt = Instant.now()
        }
        sessions.persist(s)
        return toApi(s)
    }

    @Transactional
    fun putChunk(authz: String?, uploadId: String, index: Int, req: PutChunkRequest) {
        // lock the session row so concurrent chunk PUTs serialize — otherwise parallel PUTs on different
        // indices each read the same pre-write total and could together overshoot the declared budget.
        val s = requireSessionHolder(uploadId, authz, lock = true)
        if (s.status != UploadStatus.OPEN.name) throw ApiException(409, "UPLOAD_NOT_OPEN", "The upload is already finalized")
        if (index < 0 || index >= s.totalChunks) throw ApiException(400, "INDEX_OUT_OF_RANGE", "Chunk index out of range")
        if (req.frame.suiteId != "v1") throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        if (req.frame.bytes.length > maxEncodedFrameChars) throw ApiException(400, "FRAME_TOO_LARGE", "Chunk frame exceeds the configured upload limit")
        val frame = dec.decode(req.frame.bytes)
        if (frame.size > maxFrameBytes) throw ApiException(400, "FRAME_TOO_LARGE", "Chunk frame exceeds the configured upload limit")
        ensureUploadBudget(s, index, frame.size.toLong())
        val key = "$uploadId/$index"
        blobs.put(key, frame) // idempotent overwrite — re-PUT on resume is harmless
        val existing = chunks.find(uploadId, index)
        if (existing == null) {
            chunks.persist(
                MediaChunk().apply {
                    id = UUID.randomUUID().toString(); this.uploadId = uploadId; chunkIndex = index
                    objectKey = key; byteSize = frame.size.toLong(); createdAt = Instant.now()
                },
            )
        } else {
            existing.byteSize = frame.size.toLong()
        }
    }

    @Transactional
    fun getUpload(authz: String?, uploadId: String): UploadProgress {
        val s = requireSessionHolder(uploadId, authz)
        return UploadProgress(
            uploadId = s.id,
            totalChunks = s.totalChunks,
            receivedChunks = chunks.listByUploadId(uploadId).map { it.chunkIndex },
            status = UploadProgress.Status.valueOf(s.status),
        )
    }

    @Transactional
    fun completeUpload(authz: String?, uploadId: String): ApiUploadSession {
        val s = requireSessionHolder(uploadId, authz)
        if (s.status == UploadStatus.COMPLETED.name) return toApi(s) // idempotent
        val received = chunks.listByUploadId(uploadId)
        if (received.size != s.totalChunks || received.any { it.chunkIndex < 0 || it.chunkIndex >= s.totalChunks }) {
            throw ApiException(409, "INCOMPLETE_UPLOAD", "Not all chunks have been uploaded")
        }
        s.status = UploadStatus.COMPLETED.name
        return toApi(s)
    }

    @Transactional
    fun getChunk(authz: String?, uploadId: String, index: Int): ChunkResponse {
        val s = requireSessionHolder(uploadId, authz)
        val chunk = chunks.find(uploadId, index) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk not found")
        val frame = blobs.get(chunk.objectKey) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk bytes missing")
        return ChunkResponse(frame = Blob(suiteId = "v1", bytes = enc.encodeToString(frame)))
    }

    /**
     * Reclaim abandoned uploads: OPEN sessions never finalized leak their frames in the BlobStore + manifest
     * rows forever. The scheduled GC purges sessions older than the cutoff — their blobs, then their rows.
     * COMPLETED sessions are the media's storage and are never touched here. Returns how many were purged.
     */
    @Transactional
    fun purgeStaleOpenUploads(cutoff: Instant): Int {
        val stale = sessions.listStaleOpen(cutoff)
        for (s in stale) {
            chunks.listByUploadId(s.id).forEach { blobs.delete(it.objectKey) }
            chunks.deleteByUploadId(s.id)
            sessions.deleteById(s.id)
        }
        return stale.size
    }

    private fun ensureUploadBudget(s: UploadSession, replacingIndex: Int, newFrameSize: Long) {
        val currentCiphertextBytes = chunks.listByUploadId(s.id)
            .filter { it.chunkIndex != replacingIndex }
            .sumOf { it.byteSize }
        val budget = maxCiphertextBytes(s)
        if (currentCiphertextBytes > budget || newFrameSize > budget - currentCiphertextBytes) {
            throw ApiException(400, "UPLOAD_TOO_LARGE", "Uploaded chunk data exceeds the declared or configured upload limit")
        }
    }

    private fun maxCiphertextBytes(s: UploadSession): Long =
        minOf(maxUploadBytes + (maxChunks * FRAME_OVERHEAD_BYTES.toLong()), s.byteSize + (s.totalChunks * FRAME_OVERHEAD_BYTES.toLong()))

    private fun toApi(s: UploadSession): ApiUploadSession =
        ApiUploadSession(
            uploadId = s.id, scopeId = s.scopeId, nodeId = s.nodeId, totalChunks = s.totalChunks,
            mimeType = s.mimeType, byteSize = s.byteSize,
            takenAt = OffsetDateTime.ofInstant(s.takenAt, ZoneOffset.UTC),
            status = ApiUploadSession.Status.valueOf(s.status),
            createdAt = OffsetDateTime.ofInstant(s.createdAt, ZoneOffset.UTC),
        )

    /** Resolve the session and gate on holding its scope (any key-holder may contribute, like add-media). */
    private fun requireSessionHolder(uploadId: String, authz: String?, lock: Boolean = false): UploadSession {
        val s = (if (lock) sessions.lockById(uploadId) else sessions.findById(uploadId))
            ?: throw ApiException(404, "UPLOAD_NOT_FOUND", "Upload not found")
        requireKeyHolder(s.scopeId, authz)
        return s
    }

    private fun requireMemoryNode(scopeId: String, nodeId: String) {
        val node = nodes.findById(nodeId) ?: throw ApiException(404, "NODE_NOT_FOUND", "Node not found")
        if (node.scopeId != scopeId) throw ApiException(404, "NODE_NOT_FOUND", "Node not found in this scope")
        if (node.type != "MEMORY" && node.type != "CHAPTER") {
            throw ApiException(400, "NOT_A_MEMORY", "Media can only be attached to a Memory or Chapter")
        }
    }

    private fun requireKeyHolder(scopeId: String, authz: String?): String {
        val userId = auth.resolve(authz) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "NOT_FOUND", "Scope not found")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        val tenantId = scope.tenantId
        if (tenantId != null && memberships.find(tenantId, userId)?.status != "ACTIVE") {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
        return userId
    }

    companion object {
        private const val FRAME_OVERHEAD_BYTES = 28
    }
}
