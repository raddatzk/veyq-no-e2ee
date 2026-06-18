package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.time.Instant

// Unified media storage. A Blob is a deduplicated content chunk-set (sealed frames in the BlobStore — never
// the DB); a small file is one chunk, a large file many. A Media attaches a blob to a Memory/Chapter with
// typed, client-generated derivatives (thumbnail/preview, themselves blobs). One upload/download path for all.

@Entity
@Table(name = "veyq_blob")
class Blob {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "dedup_token", nullable = false) var dedupToken: String = ""
    @Column(name = "total_chunks", nullable = false) var totalChunks: Int = 0
    @Column(name = "byte_size", nullable = false) var byteSize: Long = 0
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_blob_upload")
class BlobUpload {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "dedup_token", nullable = false) var dedupToken: String = ""
    @Column(name = "total_chunks", nullable = false) var totalChunks: Int = 0
    @Column(name = "byte_size", nullable = false) var byteSize: Long = 0
    @Column(nullable = false) var status: String = ""
    @Column(name = "blob_id") var blobId: String? = null
    @Column(name = "staged_bytes", nullable = false) var stagedBytes: Long = 0 // running sum of staged chunk bytes
    @Column(name = "suite_id") var suiteId: String? = null // crypto suite of the frames (set on first chunk)
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_blob_chunk")
class BlobChunk {
    @Id var id: String = ""
    @Column(name = "upload_id") var uploadId: String? = null
    @Column(name = "blob_id") var blobId: String? = null
    @Column(name = "chunk_index", nullable = false) var chunkIndex: Int = 0
    @Column(name = "object_key", nullable = false) var objectKey: String = ""
    @Column(name = "byte_size", nullable = false) var byteSize: Long = 0
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_media")
class Media {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "original_blob_id", nullable = false) var originalBlobId: String = ""
    // Per-media sensitive metadata ({takenAt, lat?, lng?}) sealed under the scope content key — the server
    // is blind to capture time and location. mime_type stays plaintext (needed for content typing).
    @Column(name = "sealed_meta", nullable = false) var sealedMeta: ByteArray = ByteArray(0)
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "mime_type", nullable = false) var mimeType: String = ""
    @Column(nullable = false) var highlighted: Boolean = false // server-visible favourite/star (not content)
    // Contribution attribution (ADR 0023): who added this medium ("user:<id>" / "share:<shareId>:<contributorId>")
    // and which bucket it sits in (null = the contributor's primary bucket). The server sees the keys, never names.
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "bucket_id") var bucketId: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_media_bucket")
class MediaBucket {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "owner_key", nullable = false) var ownerKey: String = ""
    @Column(name = "parent_bucket_id") var parentBucketId: String? = null
    @Column(name = "sealed_name", nullable = false) var sealedName: ByteArray = ByteArray(0)
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "is_primary", nullable = false) var isPrimary: Boolean = false
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_media_derivative")
class MediaDerivative {
    @Id var id: String = ""
    @Column(name = "media_id", nullable = false) var mediaId: String = ""
    @Column(nullable = false) var kind: String = ""
    @Column(name = "blob_id", nullable = false) var blobId: String = ""
}

enum class UploadStatus { OPEN, COMPLETED }
enum class DerivativeKind { THUMBNAIL, PREVIEW }

@ApplicationScoped
class BlobRepository : PanacheRepositoryBase<Blob, String> {
    fun lockById(id: String): Blob? = find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
    fun findByScopeAndToken(scopeId: String, dedupToken: String): Blob? =
        find("scopeId = ?1 and dedupToken = ?2", scopeId, dedupToken).firstResult()
    fun listByIds(ids: Collection<String>): List<Blob> = if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    /** Reclaimable blobs: older than [cutoff] (grace) and referenced by no media original nor derivative. */
    fun listUnreferencedOlderThan(cutoff: Instant): List<Blob> =
        list(
            "createdAt < ?1 and id not in (select m.originalBlobId from Media m) and id not in (select d.blobId from MediaDerivative d)",
            cutoff,
        )
}

@ApplicationScoped
class BlobUploadRepository : PanacheRepositoryBase<BlobUpload, String> {
    fun lockById(id: String): BlobUpload? = find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
    fun countOpenByScope(scopeId: String): Long = count("scopeId = ?1 and status = ?2", scopeId, UploadStatus.OPEN.name)
    fun listStaleOpen(cutoff: Instant): List<BlobUpload> =
        list("status = ?1 and createdAt < ?2", UploadStatus.OPEN.name, cutoff)

    /** Open uploads attributed to one writer (e.g. "share:<id>") — guest open-session quota. */
    fun countOpenByCreatedBy(createdBy: String): Long = count("createdBy = ?1 and status = ?2", createdBy, UploadStatus.OPEN.name)

    /** Total declared byteSize across a writer's uploads (open + completed) — guest per-share byte quota. */
    fun sumByteSizeByCreatedBy(createdBy: String): Long =
        getEntityManager().createQuery("select coalesce(sum(u.byteSize), 0) from BlobUpload u where u.createdBy = ?1", java.lang.Long::class.java)
            .setParameter(1, createdBy).singleResult.toLong()

    /** True if a COMPLETED upload in this scope produced [blobId] under [createdBy] — gates share contributions. */
    fun existsCompletedBy(scopeId: String, blobId: String, createdBy: String): Boolean =
        count(
            "scopeId = ?1 and blobId = ?2 and createdBy = ?3 and status = ?4",
            scopeId, blobId, createdBy, UploadStatus.COMPLETED.name,
        ) > 0
}

@ApplicationScoped
class BlobChunkRepository : PanacheRepositoryBase<BlobChunk, String> {
    fun listByUploadId(uploadId: String): List<BlobChunk> = list("uploadId = ?1 order by chunkIndex", uploadId)
    fun findByUpload(uploadId: String, chunkIndex: Int): BlobChunk? =
        find("uploadId = ?1 and chunkIndex = ?2", uploadId, chunkIndex).firstResult()
    fun findByBlob(blobId: String, chunkIndex: Int): BlobChunk? =
        find("blobId = ?1 and chunkIndex = ?2", blobId, chunkIndex).firstResult()
    fun listByBlobId(blobId: String): List<BlobChunk> = list("blobId", blobId)
    fun deleteByUploadId(uploadId: String): Long = delete("uploadId", uploadId)
    fun deleteByBlobId(blobId: String): Long = delete("blobId", blobId)
}

@ApplicationScoped
class MediaRepository : PanacheRepositoryBase<Media, String> {
    fun listByNodeId(nodeId: String): List<Media> = list("nodeId = ?1 order by createdAt", nodeId)
    fun listByNodeIds(nodeIds: Collection<String>): List<Media> = if (nodeIds.isEmpty()) emptyList() else list("nodeId in ?1", nodeIds)
    fun listByScopeId(scopeId: String): List<Media> = list("scopeId", scopeId)
    fun countByOriginalBlobId(blobId: String): Long = count("originalBlobId", blobId)
    fun countByBucketId(bucketId: String): Long = count("bucketId", bucketId)
    /** Media a contributor placed in their primary bucket of a node (bucket_id null + created_by match). */
    fun countPrimaryByOwner(nodeId: String, createdBy: String): Long =
        count("nodeId = ?1 and createdBy = ?2 and bucketId is null", nodeId, createdBy)
    fun listByNodeAndCreatedBy(nodeId: String, createdBy: String): List<Media> =
        list("nodeId = ?1 and createdBy = ?2", nodeId, createdBy)
    /** Reassign every medium in [bucketId] back to its contributor's primary bucket (null) — on sub-bucket delete. */
    fun clearBucket(bucketId: String): Int = update("bucketId = null where bucketId = ?1", bucketId)
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}

@ApplicationScoped
class MediaBucketRepository : PanacheRepositoryBase<MediaBucket, String> {
    fun listByNodeId(nodeId: String): List<MediaBucket> = list("nodeId = ?1 order by createdAt", nodeId)
    fun findPrimary(nodeId: String, ownerKey: String): MediaBucket? =
        find("nodeId = ?1 and ownerKey = ?2 and isPrimary = true", nodeId, ownerKey).firstResult()
    fun listByParent(parentBucketId: String): List<MediaBucket> = list("parentBucketId", parentBucketId)
    fun deleteByNodeIds(nodeIds: Collection<String>): Long = if (nodeIds.isEmpty()) 0 else delete("nodeId in ?1", nodeIds)
    fun deleteByScopeId(scopeId: String): Long = delete("scopeId", scopeId)
}

@ApplicationScoped
class MediaDerivativeRepository : PanacheRepositoryBase<MediaDerivative, String> {
    fun listByMediaId(mediaId: String): List<MediaDerivative> = list("mediaId", mediaId)
    fun listByMediaIds(mediaIds: Collection<String>): List<MediaDerivative> = if (mediaIds.isEmpty()) emptyList() else list("mediaId in ?1", mediaIds)
    fun countByBlobId(blobId: String): Long = count("blobId", blobId)
    fun deleteByMediaIds(mediaIds: Collection<String>): Long = if (mediaIds.isEmpty()) 0 else delete("mediaId in ?1", mediaIds)
}
