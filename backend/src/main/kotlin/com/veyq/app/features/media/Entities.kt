package com.veyq.app.features.media

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * An OPEN upload session (cr-0028): opened by `start` (which presigns a PUT per chunk under `storageKey`),
 * consumed by `complete` (stat + attach). Operator-blind: `wrappedBlobKey` is opaque ciphertext; `storageKey`
 * is a random object-key prefix. Abandoned sessions are reclaimed by GC (a later slice).
 */
@Entity
@Table(name = "veyq_upload_session")
class UploadSessionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "storage_key", nullable = false, length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "byte_size", nullable = false)
    var byteSize: Long = 0

    @Column(name = "wrapped_blob_key", nullable = false)
    lateinit var wrappedBlobKey: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<UploadSessionEntity, String>
}

/**
 * A completed blob (cr-0028): the chunk `manifest` (per-chunk ciphertext sizes, JSON array, or null when the
 * dev store has no transport) + the `K_blob` wrap. The bytes live in the object store under `storageKey/<i>`,
 * never here. Operator-blind throughout.
 */
@Entity
@Table(name = "veyq_blob")
class BlobEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "storage_key", nullable = false, length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "byte_size", nullable = false)
    var byteSize: Long = 0

    @Column(name = "wrapped_blob_key", nullable = false)
    lateinit var wrappedBlobKey: String

    @Column(name = "manifest")
    var manifest: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<BlobEntity, String>
}

/**
 * A media (cr-0028): links a blob to a node (MEMORY/CHAPTER). The share grain stays the Memory's `K_mem`;
 * this row is only the blob→node link.
 */
@Entity
@Table(name = "veyq_media")
class MediaEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, length = 36)
    lateinit var nodeId: String

    @Column(name = "blob_id", nullable = false, length = 36)
    lateinit var blobId: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<MediaEntity, String>
}
