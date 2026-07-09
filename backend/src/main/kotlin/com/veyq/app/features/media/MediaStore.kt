package com.veyq.app.features.media

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
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
    val wrappedMeta: String?,
    /** When this media last changed (cr-0107): `= createdAt` at attach, `now` on a meta edit — the keyset cursor sort key. */
    val updatedAt: Instant,
)

/** The gallery's keyset cursor (cr-0107, ADR 0033): the newest `(updatedAt, mediaId)` the client has already seen. */
data class MediaCursor(val updatedAt: Instant, val mediaId: String)

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

/** A committed blob's opaque read-back ref (cr-0073): the same shape a gallery tier carries, reused for the cover. */
data class BlobRefData(
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
)

/** The outcome of committing a cover blob: the new ref + the superseded prior blob's chunk keys to purge from the store. */
data class CoverCommitRows(val ref: BlobRefData, val reclaimKeys: List<String>)

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
        wrappedMeta: String?,
        wrappedExif: String?,
        shareIdentityId: String? = null, // cr-0101: the contributor identity, when attached via a CONTRIBUTOR share
    ): Pair<String, String> {
        val originalBlobId = persistBlob(original, originalManifest)
        val mediaId = UUID.randomUUID().toString()
        val now = Instant.now()
        MediaEntity().apply {
            id = mediaId
            scopeId = original.scopeId
            this.nodeId = nodeId
            this.blobId = originalBlobId
            this.wrappedMeta = wrappedMeta
            this.wrappedExif = wrappedExif
            this.shareIdentityId = shareIdentityId
            createdAt = now
            updatedAt = now // cr-0107: the keyset cursor starts at creation time
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
        bumpMediaRevision(nodeId) // cr-0107: an add moves media_revision (the live-updates change signal), not delete_revision
        return originalBlobId to mediaId
    }

    /**
     * Bump the node's live-updates counters (cr-0107, ADR 0033) in **one atomic UPDATE** on the `veyq_node` row,
     * so `media_revision` + `delete_revision` always read as a **consistent pair**. Every gallery-media mutation
     * moves `media_revision` (the conditional-GET change signal); a real delete additionally moves
     * `delete_revision` ([deleted] = true), which gates the O(N) id-set delete-reconcile. A cover change calls
     * neither. Joins the caller's transaction (attach/setMeta self-invoke within their `@Transactional`; the
     * share delete-own path calls it within its own). Opaque operational metadata — operator-blind.
     */
    @Transactional
    fun bumpMediaRevision(nodeId: String, deleted: Boolean = false) {
        if (deleted) {
            NodeEntity.update("mediaRevision = mediaRevision + 1, deleteRevision = deleteRevision + 1 where id = ?1", nodeId)
        } else {
            NodeEntity.update("mediaRevision = mediaRevision + 1 where id = ?1", nodeId)
        }
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

    /**
     * The node's media as a **keyset delta** past [since] (cr-0107, ADR 0033): rows whose `updated_at` is strictly
     * greater than the cursor (id as the tie-break), oldest-first so the client advances its cursor to the last
     * row. [since] = null ⇒ the full list (first load). Ordered by `(updated_at, id)`, the cursor's sort key.
     */
    fun listNodeMediaSince(scopeId: String, nodeId: String, since: MediaCursor?): List<MediaRow> {
        val media = if (since == null) {
            MediaEntity.find("scopeId = ?1 and nodeId = ?2 order by updatedAt, id", scopeId, nodeId).list()
        } else {
            MediaEntity.find(
                "scopeId = ?1 and nodeId = ?2 and (updatedAt > ?3 or (updatedAt = ?3 and id > ?4)) order by updatedAt, id",
                scopeId, nodeId, since.updatedAt, since.mediaId,
            ).list()
        }
        return media.mapNotNull(::toRow)
    }

    /**
     * Every current `mediaId` of the node (ids only) — the delete-reconcile set (cr-0107). O(N), so the handler
     * asks for it **only when `delete_revision` advanced** (a real removal); the client drops any cached id it no
     * longer sees, catching a delete and a net-zero delete+add.
     */
    fun listNodeMediaIds(scopeId: String, nodeId: String): List<String> =
        MediaEntity.find("scopeId = ?1 and nodeId = ?2", scopeId, nodeId).list().map { it.id }

    /** Map a media entity to its read-back row, resolving the original + derivative blobs; null when the original blob is missing. */
    private fun toRow(m: MediaEntity): MediaRow? {
        val blob = BlobEntity.findById(m.blobId) ?: return null
        val derivatives = MediaDerivativeEntity.find("mediaId = ?1", m.id).list().mapNotNull { d ->
            val db = BlobEntity.findById(d.blobId) ?: return@mapNotNull null
            DerivativeRow(d.kind, db.id, db.chunkCount, db.byteSize, db.wrappedBlobKey, decodeManifest(db.manifest))
        }
        return MediaRow(
            m.id, blob.id, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey, decodeManifest(blob.manifest),
            derivatives, m.wrappedMeta, m.updatedAt ?: m.createdAt, // cr-0107: coalesce for pre-backfill safety
        )
    }

    /** The media, if it exists in this scope — carries the lazy `wrappedExif` for the lightbox exif endpoint. */
    fun findMedia(scopeId: String, mediaId: String): MediaEntity? =
        MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId }

    /** Overwrite a media's sealed `wrappedMeta` verbatim (opaque; e.g. a re-sealed corrected date). */
    @Transactional
    fun setMeta(scopeId: String, mediaId: String, wrappedMeta: String?): MediaEntity? {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return null
        media.wrappedMeta = wrappedMeta
        media.updatedAt = Instant.now() // cr-0107: the edit advances the keyset cursor, so the live poll re-fetches it
        media.persist()
        bumpMediaRevision(media.nodeId) // a meta edit moves media_revision, not delete_revision
        return media
    }

    fun findBlob(scopeId: String, blobId: String): BlobEntity? =
        BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId }

    /**
     * Commit a verified session as the node's **standalone cover** blob (cr-0073): persist one `veyq_blob`
     * (NO `veyq_media` row, so it never lists in the gallery), point the node's `cover_blob_id` at it, and
     * **atomically reclaim** any prior cover blob (its row is deleted here; its store objects are purged by the
     * caller via the returned chunk keys). Consumes the upload session. Returns the new ref + the reclaim keys.
     */
    @Transactional
    fun commitCover(nodeId: String, session: UploadSessionEntity, manifest: List<Long>?): CoverCommitRows {
        val node = NodeEntity.findById(nodeId)!! // resolved by the handler before verify; reloaded in-tx
        val reclaimKeys = deleteCoverBlobRow(node.coverBlobId)
        val blobId = persistBlob(session, manifest)
        node.coverBlobId = blobId
        node.coverByteSize = session.byteSize
        node.persist()
        UploadSessionEntity.deleteById(session.id)
        return CoverCommitRows(
            BlobRefData(blobId, session.chunkCount, session.byteSize, session.wrappedBlobKey, manifest),
            reclaimKeys,
        )
    }

    /**
     * Detach the node's cover blob (cr-0073): clear its `cover_blob_id`/`cover_byte_size` and delete the
     * `veyq_blob` row. Returns the blob's chunk keys for a best-effort store purge (empty when the node — or
     * scope — has no cover blob; idempotent). Null node ⇒ empty (the endpoint answers 204 either way).
     */
    @Transactional
    fun clearCover(scopeId: String, nodeId: String): List<String> {
        val node = NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId } ?: return emptyList()
        val reclaimKeys = deleteCoverBlobRow(node.coverBlobId)
        node.coverBlobId = null
        node.coverByteSize = null
        node.persist()
        return reclaimKeys
    }

    /** Delete a cover blob row by id (if present) and return its chunk keys for a best-effort store purge. */
    private fun deleteCoverBlobRow(blobId: String?): List<String> {
        val blob = blobId?.let { BlobEntity.findById(it) } ?: return emptyList()
        val keys = (0 until blob.chunkCount).map { chunkKey(blob.storageKey, it) }
        BlobEntity.deleteById(blob.id)
        return keys
    }

    // Manifest = a JSON array of per-chunk ciphertext sizes; tiny and integer-only, so encoded by hand.
    private fun encodeManifest(sizes: List<Long>): String = sizes.joinToString(",", "[", "]")

    private fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }
}
