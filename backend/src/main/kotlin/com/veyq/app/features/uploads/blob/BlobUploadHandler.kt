package com.veyq.app.features.uploads.blob

import com.veyq.app.generated.model.Blob as ApiBlob
import com.veyq.app.generated.model.BlobTicket
import com.veyq.app.generated.model.BlobUploadProgress
import com.veyq.app.generated.model.ChunkResponse
import com.veyq.app.generated.model.CompleteBlobResponse
import com.veyq.app.generated.model.PutChunkRequest
import com.veyq.app.generated.model.StartBlobRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobChunk
import com.veyq.app.shared.content.BlobChunkRepository
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.BlobUpload
import com.veyq.app.shared.content.BlobUploadRepository
import com.veyq.app.shared.content.ScopeContentKeyRepository
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
import java.util.Base64
import java.util.UUID

/**
 * uc.uploads (unified blob layer) — store ANY content as a deduplicated chunk-set: a small file is one
 * frame, a large file many, both resumable. The client computes the dedup token over the whole plaintext;
 * if the scope already holds that blob the upload is skipped entirely. Frames are sealed AEAD and live in
 * the BlobStore (never the DB); the DB holds only the session + manifest. The server is blind to the bytes.
 */
@ApplicationScoped
class BlobUploadHandler(
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val contentKeys: ScopeContentKeyRepository,
    private val blobs: BlobRepository,
    private val uploads: BlobUploadRepository,
    private val chunks: BlobChunkRepository,
    private val store: BlobStore,
    private val memberships: MembershipRepository,
    private val auth: SessionService,
    @param:ConfigProperty(name = "veyq.uploads.max-chunks", defaultValue = "10000") private val maxChunks: Int,
    @param:ConfigProperty(name = "veyq.uploads.max-upload-bytes", defaultValue = "10737418240") private val maxUploadBytes: Long,
    @param:ConfigProperty(name = "veyq.uploads.max-frame-bytes", defaultValue = "16777244") private val maxFrameBytes: Int,
    @param:ConfigProperty(name = "veyq.uploads.max-open-sessions", defaultValue = "100") private val maxOpenSessions: Long,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val maxEncodedFrameChars = ((maxFrameBytes + 2) / 3) * 4

    @Transactional
    fun startBlob(authz: String?, scopeId: String, req: StartBlobRequest): BlobTicket {
        requireKeyHolder(scopeId, authz)
        return startBlobAs(scopeId, auth.resolve(authz)!!, req)
    }

    /** Start a blob upload for an already-authorized writer (session key holder or a CONTRIBUTOR share). */
    @Transactional
    fun startBlobAs(scopeId: String, writerId: String, req: StartBlobRequest): BlobTicket {
        contentKeys.findByScopeId(scopeId) ?: throw ApiException(409, "CONTENT_KEY_REQUIRED", "Provision the scope content key before uploading")
        if (req.totalChunks <= 0 || req.totalChunks > maxChunks) throw ApiException(400, "INVALID_CHUNK_COUNT", "totalChunks out of range")
        if (req.byteSize < 0 || req.byteSize > maxUploadBytes) throw ApiException(400, "INVALID_SIZE", "byteSize out of range")

        // dedup at the start: if the scope already holds this content, no upload is needed at all
        blobs.findByScopeAndToken(scopeId, req.dedupToken)?.let {
            return BlobTicket(status = BlobTicket.Status.READY, blobId = it.id, totalChunks = it.totalChunks)
        }
        if (uploads.countOpenByScope(scopeId) >= maxOpenSessions) {
            throw ApiException(429, "TOO_MANY_OPEN_UPLOADS", "Too many in-progress uploads — finish or abandon some first")
        }
        val u = BlobUpload().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; dedupToken = req.dedupToken
            totalChunks = req.totalChunks; byteSize = req.byteSize; status = UploadStatus.OPEN.name
            createdBy = writerId; createdAt = Instant.now()
        }
        uploads.persist(u)
        return BlobTicket(status = BlobTicket.Status.UPLOADING, uploadId = u.id, totalChunks = u.totalChunks)
    }

    @Transactional
    fun putBlobChunk(authz: String?, scopeId: String, uploadId: String, index: Int, req: PutChunkRequest) {
        requireKeyHolder(scopeId, authz)
        putBlobChunkAs(scopeId, uploadId, index, req)
    }

    @Transactional
    fun putBlobChunkAs(scopeId: String, uploadId: String, index: Int, req: PutChunkRequest) {
        val u = requireUploadInScope(scopeId, uploadId, lock = true)
        if (u.status != UploadStatus.OPEN.name) throw ApiException(409, "UPLOAD_NOT_OPEN", "The upload is already finalized")
        if (index < 0 || index >= u.totalChunks) throw ApiException(400, "INDEX_OUT_OF_RANGE", "Chunk index out of range")
        if (req.frame.suiteId != "v1") throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        if (req.frame.bytes.length > maxEncodedFrameChars) throw ApiException(400, "FRAME_TOO_LARGE", "Chunk frame too large")
        val frame = dec.decode(req.frame.bytes)
        if (frame.size > maxFrameBytes) throw ApiException(400, "FRAME_TOO_LARGE", "Chunk frame too large")
        val existing = chunks.findByUpload(uploadId, index)
        // O(1) budget check off the running counter (re-PUT of a chunk swaps its old size for the new one).
        val newStaged = u.stagedBytes - (existing?.byteSize ?: 0) + frame.size.toLong()
        ensureBudget(u, newStaged)
        val key = "$uploadId/$index"
        store.put(key, frame) // idempotent overwrite — re-PUT on resume is harmless
        if (existing == null) {
            chunks.persist(BlobChunk().apply {
                id = UUID.randomUUID().toString(); this.uploadId = uploadId; chunkIndex = index
                objectKey = key; byteSize = frame.size.toLong(); createdAt = Instant.now()
            })
        } else {
            existing.byteSize = frame.size.toLong()
        }
        u.stagedBytes = newStaged
    }

    @Transactional
    fun getBlobUpload(authz: String?, scopeId: String, uploadId: String): BlobUploadProgress {
        requireKeyHolder(scopeId, authz)
        return getBlobUploadAs(scopeId, uploadId)
    }

    @Transactional
    fun getBlobUploadAs(scopeId: String, uploadId: String): BlobUploadProgress {
        val u = requireUploadInScope(scopeId, uploadId)
        return BlobUploadProgress(
            uploadId = u.id, totalChunks = u.totalChunks,
            receivedChunks = chunks.listByUploadId(uploadId).map { it.chunkIndex },
            status = BlobUploadProgress.Status.valueOf(u.status),
        )
    }

    @Transactional
    fun completeBlobUpload(authz: String?, scopeId: String, uploadId: String): CompleteBlobResponse {
        requireKeyHolder(scopeId, authz)
        return completeBlobUploadAs(scopeId, uploadId)
    }

    @Transactional
    fun completeBlobUploadAs(scopeId: String, uploadId: String): CompleteBlobResponse {
        val u = requireUploadInScope(scopeId, uploadId, lock = true)
        if (u.status == UploadStatus.COMPLETED.name) {
            val b = blobs.findById(u.blobId!!)!!
            return CompleteBlobResponse(blobId = b.id, totalChunks = b.totalChunks, byteSize = b.byteSize)
        }
        val staged = chunks.listByUploadId(uploadId)
        if (staged.size != u.totalChunks || staged.map { it.chunkIndex }.toSet() != (0 until u.totalChunks).toSet()) {
            throw ApiException(409, "INCOMPLETE_UPLOAD", "Not all chunks have been uploaded")
        }
        // dedup at finalize too: another upload may have stored this content meanwhile → discard ours.
        scopes.lockById(scopeId)
        val existing = blobs.findByScopeAndToken(scopeId, u.dedupToken)
        val blob = if (existing != null) {
            staged.forEach { store.delete(it.objectKey) }
            chunks.deleteByUploadId(uploadId)
            existing
        } else {
            val b = Blob().apply {
                id = UUID.randomUUID().toString(); this.scopeId = scopeId; dedupToken = u.dedupToken
                totalChunks = u.totalChunks; byteSize = u.byteSize; suiteId = "v1"; createdAt = Instant.now()
            }.also(blobs::persist)
            staged.forEach { it.blobId = b.id; it.uploadId = null } // promote the frames from upload to blob
            b
        }
        u.status = UploadStatus.COMPLETED.name; u.blobId = blob.id
        return CompleteBlobResponse(blobId = blob.id, totalChunks = blob.totalChunks, byteSize = blob.byteSize)
    }

    @Transactional
    fun getBlobChunk(authz: String?, scopeId: String, blobId: String, index: Int): ChunkResponse {
        requireKeyHolder(scopeId, authz)
        val blob = blobs.findById(blobId) ?: throw ApiException(404, "BLOB_NOT_FOUND", "Blob not found")
        if (blob.scopeId != scopeId) throw ApiException(404, "BLOB_NOT_FOUND", "Blob not found in this scope")
        val chunk = chunks.findByBlob(blobId, index) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk not found")
        val frame = store.get(chunk.objectKey) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk bytes missing")
        return ChunkResponse(frame = ApiBlob(suiteId = "v1", bytes = enc.encodeToString(frame)))
    }

    /** Scheduled GC: reclaim abandoned OPEN uploads (frames + manifest + session). COMPLETED are kept. */
    @Transactional
    fun purgeStaleOpenUploads(cutoff: Instant): Int {
        val stale = uploads.listStaleOpen(cutoff)
        for (u in stale) {
            chunks.listByUploadId(u.id).forEach { store.delete(it.objectKey) }
            chunks.deleteByUploadId(u.id)
            uploads.deleteById(u.id)
        }
        return stale.size
    }

    private fun ensureBudget(u: BlobUpload, newStagedBytes: Long) {
        val budget = minOf(maxUploadBytes + maxChunks * 28L, u.byteSize + u.totalChunks * 28L)
        if (newStagedBytes > budget) {
            throw ApiException(400, "UPLOAD_TOO_LARGE", "Uploaded chunk data exceeds the declared or configured limit")
        }
    }

    private fun requireUploadInScope(scopeId: String, uploadId: String, lock: Boolean = false): BlobUpload {
        val u = (if (lock) uploads.lockById(uploadId) else uploads.findById(uploadId))
            ?: throw ApiException(404, "UPLOAD_NOT_FOUND", "Upload not found")
        if (u.scopeId != scopeId) throw ApiException(404, "UPLOAD_NOT_FOUND", "Upload not found in this scope")
        return u
    }

    private fun requireKeyHolder(scopeId: String, authz: String?) {
        val userId = auth.resolve(authz) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val scope = scopes.findById(scopeId) ?: throw ApiException(404, "NOT_FOUND", "Scope not found")
        grants.find(scope.id, userId) ?: throw ApiException(403, "NOT_A_KEY_HOLDER", "You do not hold this scope")
        val tenantId = scope.tenantId
        if (tenantId != null && memberships.find(tenantId, userId)?.status != "ACTIVE") {
            throw ApiException(403, "NOT_A_MEMBER", "You are not an active member of this tenant")
        }
    }
}
