package com.veyq.app.features.uploads.blob

import com.veyq.app.generated.model.Blob as ApiBlob
import com.veyq.app.generated.model.BlobTicket
import com.veyq.app.generated.model.BlobUploadProgress
import com.veyq.app.generated.model.ChunkResponse
import com.veyq.app.generated.model.CompleteBlobResponse
import com.veyq.app.generated.model.PresignedPut
import com.veyq.app.generated.model.PutChunkRequest
import com.veyq.app.generated.model.StartBlobRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.Blob
import com.veyq.app.shared.content.BlobChunk
import com.veyq.app.shared.content.BlobChunkRepository
import com.veyq.app.shared.content.BlobRepository
import com.veyq.app.shared.content.BlobUpload
import com.veyq.app.shared.content.BlobUploadRepository
import com.veyq.app.shared.content.MediaDerivativeRepository
import com.veyq.app.shared.content.MediaRepository
import com.veyq.app.shared.content.ScopeContentKeyRepository
import com.veyq.app.shared.content.UploadStatus
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.ScopeAccessGuard
import com.veyq.app.shared.storage.BlobStore
import jakarta.enterprise.context.ApplicationScoped
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.transaction.Transactional
import com.veyq.app.shared.config.UploadsConfig
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
    private val contentKeys: ScopeContentKeyRepository,
    private val blobs: BlobRepository,
    private val uploads: BlobUploadRepository,
    private val chunks: BlobChunkRepository,
    private val media: MediaRepository,
    private val derivatives: MediaDerivativeRepository,
    private val store: BlobStore,
    private val guard: ScopeAccessGuard,
    private val suites: CryptoSuiteRegistry,
    cfg: UploadsConfig,
) {
    private val maxChunks = cfg.maxChunks()
    private val maxUploadBytes = cfg.maxUploadBytes()
    private val maxFrameBytes = cfg.maxFrameBytes()
    private val maxOpenSessions = cfg.maxOpenSessions()
    // Guest (public-share) write quotas — tighter than the per-scope caps, since a share link is unauthenticated.
    private val maxShareOpenSessions = cfg.maxShareOpenSessions()
    private val maxShareBytes = cfg.maxShareBytes()
    private val presignTtl = cfg.presignTtlSeconds()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val maxEncodedFrameChars = ((maxFrameBytes + 2) / 3) * 4

    @Transactional
    fun startBlob(authz: String?, scopeId: String, req: StartBlobRequest): BlobTicket {
        val writerId = requireKeyHolder(scopeId, authz)
        return startBlobAs(scopeId, writerId, req)
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
        // Unauthenticated guest (CONTRIBUTOR/UPLOADER share) writes get a tighter per-SHARE cap so one link can't
        // open up to maxOpenSessions × maxUploadBytes of staged junk (the per-scope cap is shared with the owner).
        if (writerId.startsWith("share:")) {
            if (uploads.countOpenByCreatedBy(writerId) >= maxShareOpenSessions) {
                throw ApiException(429, "TOO_MANY_OPEN_UPLOADS", "Too many in-progress uploads for this share")
            }
            if (uploads.sumByteSizeByCreatedBy(writerId) + req.byteSize > maxShareBytes) {
                throw ApiException(429, "SHARE_QUOTA_EXCEEDED", "This share has reached its upload quota")
            }
        }
        // A presigned (direct-to-store) upload declares its frame suite up front (raw frames carry none); a
        // proxied upload leaves it null and derives it from the frame bodies. Validate if present.
        req.suiteId?.let { if (!suites.isKnown(it)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite") }
        val u = BlobUpload().apply {
            id = UUID.randomUUID().toString(); this.scopeId = scopeId; dedupToken = req.dedupToken
            totalChunks = req.totalChunks; byteSize = req.byteSize; status = UploadStatus.OPEN.name
            createdBy = writerId; createdAt = Instant.now(); suiteId = req.suiteId
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
    fun putBlobChunkAs(scopeId: String, uploadId: String, index: Int, req: PutChunkRequest, expectedOwner: String? = null) {
        val u = requireUploadInScope(scopeId, uploadId, lock = true, expectedOwner = expectedOwner)
        if (u.status != UploadStatus.OPEN.name) throw ApiException(409, "UPLOAD_NOT_OPEN", "The upload is already finalized")
        if (index < 0 || index >= u.totalChunks) throw ApiException(400, "INDEX_OUT_OF_RANGE", "Chunk index out of range")
        if (!suites.isKnown(req.frame.suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite")
        // all frames of one upload seal the same content stream → one suite; reject a mid-upload suite switch.
        if (u.suiteId != null && u.suiteId != req.frame.suiteId) throw ApiException(400, "SUITE_MISMATCH", "All chunks must share one crypto suite")
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
        u.suiteId = req.frame.suiteId
    }

    @Transactional
    fun getBlobUpload(authz: String?, scopeId: String, uploadId: String): BlobUploadProgress {
        requireKeyHolder(scopeId, authz)
        return getBlobUploadAs(scopeId, uploadId)
    }

    @Transactional
    fun getBlobUploadAs(scopeId: String, uploadId: String, expectedOwner: String? = null): BlobUploadProgress {
        val u = requireUploadInScope(scopeId, uploadId, expectedOwner = expectedOwner)
        val have = chunks.listByUploadId(uploadId).map { it.chunkIndex }.toMutableSet()
        // Presigned (direct-to-store) chunks have NO manifest row until complete — stat the store so resume
        // counts chunks already PUT directly to S3 as received (and we only presign the truly-missing ones).
        if (store.supportsPresign() && u.status == UploadStatus.OPEN.name) {
            for (i in 0 until u.totalChunks) if (i !in have && store.stat("$uploadId/$i") != null) have.add(i)
        }
        // Direct-to-store fast path: a presigned PUT URL per still-missing chunk (ADR 0021). Empty when the
        // store can't presign (in-memory dev backend) → the client falls back to the proxied chunk endpoint.
        val presigned = if (u.status == UploadStatus.OPEN.name) {
            (0 until u.totalChunks).filter { it !in have }
                .mapNotNull { i -> store.presignPut("$uploadId/$i", presignTtl)?.let { PresignedPut(index = i, url = it) } }
        } else {
            emptyList()
        }
        return BlobUploadProgress(
            uploadId = u.id, totalChunks = u.totalChunks,
            receivedChunks = have.toList().sorted(),
            status = BlobUploadProgress.Status.valueOf(u.status),
            presignedPuts = presigned,
        )
    }

    @Transactional
    fun completeBlobUpload(authz: String?, scopeId: String, uploadId: String): CompleteBlobResponse {
        requireKeyHolder(scopeId, authz)
        return completeBlobUploadAs(scopeId, uploadId)
    }

    @Transactional
    fun completeBlobUploadAs(scopeId: String, uploadId: String, expectedOwner: String? = null): CompleteBlobResponse {
        val u = requireUploadInScope(scopeId, uploadId, lock = true, expectedOwner = expectedOwner)
        if (u.status == UploadStatus.COMPLETED.name) {
            val b = blobs.findById(u.blobId!!)!!
            return CompleteBlobResponse(blobId = b.id, totalChunks = b.totalChunks, byteSize = b.byteSize)
        }
        reconcileDirectUploads(u) // presigned frames land in S3 without a manifest row — stat + record them first
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
                totalChunks = u.totalChunks; byteSize = u.byteSize
                suiteId = u.suiteId ?: throw ApiException(409, "INCOMPLETE_UPLOAD", "Upload has no sealed frames")
                createdAt = Instant.now()
            }.also(blobs::persist)
            staged.forEach { it.blobId = b.id; it.uploadId = null } // promote the frames from upload to blob
            b
        }
        u.status = UploadStatus.COMPLETED.name; u.blobId = blob.id
        return CompleteBlobResponse(blobId = blob.id, totalChunks = blob.totalChunks, byteSize = blob.byteSize)
    }

    fun getBlobChunk(authz: String?, scopeId: String, blobId: String, index: Int): ChunkResponse {
        // Resolve the chunk's metadata (auth + ACL + object key + suite) in a short transaction, then read the
        // bytes from the store OUTSIDE it — so the DB connection isn't held across blob-store IO (which becomes
        // a network round-trip once the store is remote). This is the hot download path (every gallery tile).
        val (objectKey, suiteId) = QuarkusTransaction.requiringNew().call {
            requireKeyHolder(scopeId, authz)
            val blob = blobs.findById(blobId) ?: throw ApiException(404, "BLOB_NOT_FOUND", "Blob not found")
            if (blob.scopeId != scopeId) throw ApiException(404, "BLOB_NOT_FOUND", "Blob not found in this scope")
            val chunk = chunks.findByBlob(blobId, index) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk not found")
            chunk.objectKey to blob.suiteId
        }
        val frame = store.get(objectKey) ?: throw ApiException(404, "CHUNK_NOT_FOUND", "Chunk bytes missing")
        return ChunkResponse(frame = ApiBlob(suiteId = suiteId, bytes = enc.encodeToString(frame)))
    }

    /**
     * True if [blobId] was produced by a COMPLETED upload in this scope tagged [createdBy] (e.g. "share:<id>").
     * Dedup-safe: even when a share upload dedups onto a pre-existing blob, the completed upload row records
     * that this contributor legitimately produced that exact content — so it gates a guest to the blobs THEY
     * uploaded, not arbitrary scope blobs they happen to know the id of.
     */
    fun wasUploadedBy(scopeId: String, blobId: String, createdBy: String): Boolean =
        uploads.existsCompletedBy(scopeId, blobId, createdBy)

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

    /**
     * Scheduled GC: reclaim finalized blobs no media (original or derivative) references anymore — the frames
     * a deleted medium left behind (blobs are dedup'd, so deleting media only drops the reference, ADR 0007).
     * Gated by a grace [cutoff] on the blob's own createdAt so a just-finalized blob whose media isn't attached
     * yet is never reaped. Per blob: lock its scope + re-check the reference count (serializes against the
     * dedup-at-finalize path, which also locks the scope), then delete frames + chunk rows + the blob row.
     */
    @Transactional
    fun purgeUnreferencedBlobs(cutoff: Instant): Int {
        var removed = 0
        for (b in blobs.listUnreferencedOlderThan(cutoff)) {
            scopes.lockById(b.scopeId)
            if (media.countByOriginalBlobId(b.id) > 0 || derivatives.countByBlobId(b.id) > 0) continue // attached since
            chunks.listByBlobId(b.id).forEach { store.delete(it.objectKey) }
            chunks.deleteByBlobId(b.id)
            blobs.deleteById(b.id)
            removed++
        }
        return removed
    }

    /**
     * Presigned (direct-to-store) frames are PUT straight to S3 by the client, so no manifest row exists at
     * complete time. For each missing chunk, stat its object: if present record a manifest row (size from the
     * stat) so the existing completeness check + finalize work unchanged. Enforce the per-frame and cumulative
     * caps here (the proxied per-PUT checks never ran), purging the staged objects on abuse. A no-op for a
     * fully-proxied upload (every chunk already has a row).
     */
    private fun reconcileDirectUploads(u: BlobUpload) {
        val have = chunks.listByUploadId(u.id).map { it.chunkIndex }.toMutableSet()
        for (i in 0 until u.totalChunks) {
            if (i in have) continue
            val key = "${u.id}/$i"
            val size = store.stat(key) ?: continue // still missing → caught by the completeness check
            if (size > maxFrameBytes) { store.delete(key); throw ApiException(400, "FRAME_TOO_LARGE", "Uploaded frame exceeds the limit") }
            chunks.persist(
                BlobChunk().apply {
                    id = UUID.randomUUID().toString(); uploadId = u.id; chunkIndex = i; objectKey = key; byteSize = size; createdAt = Instant.now()
                },
            )
            u.stagedBytes += size
            have.add(i)
        }
        try {
            ensureBudget(u, u.stagedBytes)
        } catch (e: ApiException) {
            chunks.listByUploadId(u.id).forEach { store.delete(it.objectKey) }
            chunks.deleteByUploadId(u.id)
            throw e
        }
    }

    private fun ensureBudget(u: BlobUpload, newStagedBytes: Long) {
        val budget = minOf(maxUploadBytes + maxChunks * 28L, u.byteSize + u.totalChunks * 28L)
        if (newStagedBytes > budget) {
            throw ApiException(400, "UPLOAD_TOO_LARGE", "Uploaded chunk data exceeds the declared or configured limit")
        }
    }

    private fun requireUploadInScope(scopeId: String, uploadId: String, lock: Boolean = false, expectedOwner: String? = null): BlobUpload {
        val u = (if (lock) uploads.lockById(uploadId) else uploads.findById(uploadId))
            ?: throw ApiException(404, "UPLOAD_NOT_FOUND", "Upload not found")
        if (u.scopeId != scopeId) throw ApiException(404, "UPLOAD_NOT_FOUND", "Upload not found in this scope")
        // Guest contributions pass the owning "share:<id>" so one guest can't touch another's in-flight upload
        // in the same scope (members pass null — a scope key-holder may write the scope anyway). 404, not 403,
        // so the response can't be used to probe whether an uploadId exists under a different owner.
        if (expectedOwner != null && u.createdBy != expectedOwner) throw ApiException(404, "UPLOAD_NOT_FOUND", "Upload not found in this scope")
        return u
    }

    private fun requireKeyHolder(scopeId: String, authz: String?): String =
        guard.requireKeyHolder(authz, scopeId)
}
