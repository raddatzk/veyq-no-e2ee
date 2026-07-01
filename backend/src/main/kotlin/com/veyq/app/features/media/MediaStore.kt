package com.veyq.app.features.media

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.content.NodeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A node's media for read-back: the original blob's metadata + wrap, plus its role-tagged derivative tiers. */
data class MediaRow(
    val mediaId: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
    val derivatives: List<DerivativeRow>,
    val wrappedLayout: String?,
    val wrappedCapturedAt: String?,
)

/** One role-tagged derivative tier of a media (its own blob metadata + wrap). */
data class DerivativeRow(
    val kind: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
)

/** A verified derivative upload to attach: its role + session + the verified per-chunk manifest. */
data class DerivativeAttach(val kind: String, val session: UploadSessionEntity, val manifest: List<Long>?)

/**
 * Persistence for media blobs (cr-0028). Operator-blind: stores ciphertext wraps + opaque metadata only.
 * Scope access = the caller holds a membership for the scope (cr-0026). The manifest (per-chunk ciphertext
 * sizes) is held as a small JSON array of integers — itself only ciphertext-size metadata.
 */
@ApplicationScoped
class MediaStore {

    fun isMember(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0

    /** The node, if it exists in this scope (its `kind` decides the media-target rule). */
    fun findNode(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }

    @Transactional
    fun createSession(scopeId: String, storageKey: String, chunkCount: Int, byteSize: Long, wrappedBlobKey: String): String {
        val id = UUID.randomUUID().toString()
        UploadSessionEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.storageKey = storageKey
            this.chunkCount = chunkCount
            this.byteSize = byteSize
            this.wrappedBlobKey = wrappedBlobKey
            createdAt = Instant.now()
        }.persist()
        return id
    }

    fun findSession(scopeId: String, uploadId: String): UploadSessionEntity? =
        UploadSessionEntity.findById(uploadId)?.takeIf { it.scopeId == scopeId }

    /**
     * Promote a media's verified sessions to blobs + attach to the node, consuming every session. The original
     * session becomes `veyq_media.blob_id`; each derivative becomes its own blob + a `veyq_media_derivative`
     * row. One transaction — all blobs, the media, and the derivative rows commit together. Returns
     * (originalBlobId, mediaId).
     */
    @Transactional
    fun attach(
        original: UploadSessionEntity,
        nodeId: String,
        originalManifest: List<Long>?,
        derivatives: List<DerivativeAttach>,
        wrappedLayout: String?,
        wrappedCapturedAt: String?,
        wrappedExif: String?,
    ): Pair<String, String> {
        val originalBlobId = persistBlob(original, originalManifest)
        val mediaId = UUID.randomUUID().toString()
        MediaEntity().apply {
            id = mediaId
            scopeId = original.scopeId
            this.nodeId = nodeId
            this.blobId = originalBlobId
            this.wrappedLayout = wrappedLayout
            this.wrappedCapturedAt = wrappedCapturedAt
            this.wrappedExif = wrappedExif
            createdAt = Instant.now()
        }.persist()
        for (d in derivatives) {
            val derivBlobId = persistBlob(d.session, d.manifest)
            MediaDerivativeEntity().apply {
                id = UUID.randomUUID().toString()
                this.mediaId = mediaId
                kind = d.kind
                blobId = derivBlobId
                createdAt = Instant.now()
            }.persist()
        }
        UploadSessionEntity.deleteById(original.id)
        derivatives.forEach { UploadSessionEntity.deleteById(it.session.id) }
        return originalBlobId to mediaId
    }

    /** Persist a blob row from a verified session; returns the new blob id. */
    private fun persistBlob(session: UploadSessionEntity, manifest: List<Long>?): String {
        val blobId = UUID.randomUUID().toString()
        BlobEntity().apply {
            id = blobId
            scopeId = session.scopeId
            storageKey = session.storageKey
            chunkCount = session.chunkCount
            byteSize = session.byteSize
            wrappedBlobKey = session.wrappedBlobKey
            this.manifest = manifest?.let { encodeManifest(it) }
            createdAt = Instant.now()
        }.persist()
        return blobId
    }

    fun listNodeMedia(scopeId: String, nodeId: String): List<MediaRow> {
        val media = MediaEntity.find("scopeId = ?1 and nodeId = ?2", scopeId, nodeId).list()
        return media.mapNotNull { m ->
            val blob = BlobEntity.findById(m.blobId) ?: return@mapNotNull null
            val derivatives = MediaDerivativeEntity.find("mediaId = ?1", m.id).list().mapNotNull { d ->
                val db = BlobEntity.findById(d.blobId) ?: return@mapNotNull null
                DerivativeRow(d.kind, db.id, db.chunkCount, db.byteSize, db.wrappedBlobKey, decodeManifest(db.manifest))
            }
            MediaRow(m.id, blob.id, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey, decodeManifest(blob.manifest), derivatives, m.wrappedLayout, m.wrappedCapturedAt)
        }
    }

    /** The media, if it exists in this scope — carries the lazy `wrappedExif` for the lightbox meta endpoint. */
    fun findMedia(scopeId: String, mediaId: String): MediaEntity? =
        MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId }

    /** Overwrite a media's sealed `wrappedCapturedAt` verbatim (opaque; e.g. a re-sealed corrected date). */
    @Transactional
    fun setCapturedAt(scopeId: String, mediaId: String, wrappedCapturedAt: String?): MediaEntity? {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return null
        media.wrappedCapturedAt = wrappedCapturedAt
        media.persist()
        return media
    }

    fun findBlob(scopeId: String, blobId: String): BlobEntity? =
        BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId }

    // Manifest = a JSON array of per-chunk ciphertext sizes; tiny and integer-only, so encoded by hand.
    private fun encodeManifest(sizes: List<Long>): String = sizes.joinToString(",", "[", "]")

    private fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }
}
