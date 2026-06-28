package com.veyq.app.features.media

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.content.NodeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A node's media for read-back (blob metadata + the K_blob wrap). */
data class MediaRow(
    val mediaId: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
)

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

    /** Promote a verified session to a blob + attach it to the node, consuming the session. Returns ids. */
    @Transactional
    fun attach(session: UploadSessionEntity, nodeId: String, manifest: List<Long>?): Pair<String, String> {
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
        val mediaId = UUID.randomUUID().toString()
        MediaEntity().apply {
            id = mediaId
            scopeId = session.scopeId
            this.nodeId = nodeId
            this.blobId = blobId
            createdAt = Instant.now()
        }.persist()
        UploadSessionEntity.deleteById(session.id)
        return blobId to mediaId
    }

    fun listNodeMedia(scopeId: String, nodeId: String): List<MediaRow> {
        val media = MediaEntity.find("scopeId = ?1 and nodeId = ?2", scopeId, nodeId).list()
        return media.mapNotNull { m ->
            val blob = BlobEntity.findById(m.blobId) ?: return@mapNotNull null
            MediaRow(m.id, blob.id, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey, decodeManifest(blob.manifest))
        }
    }

    fun findBlob(scopeId: String, blobId: String): BlobEntity? =
        BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId }

    // Manifest = a JSON array of per-chunk ciphertext sizes; tiny and integer-only, so encoded by hand.
    private fun encodeManifest(sizes: List<Long>): String = sizes.joinToString(",", "[", "]")

    private fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }
}
