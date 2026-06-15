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
    fun findByScopeAndToken(scopeId: String, dedupToken: String): Blob? =
        find("scopeId = ?1 and dedupToken = ?2", scopeId, dedupToken).firstResult()
}

@ApplicationScoped
class BlobUploadRepository : PanacheRepositoryBase<BlobUpload, String> {
    fun lockById(id: String): BlobUpload? = find("id", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
    fun countOpenByScope(scopeId: String): Long = count("scopeId = ?1 and status = ?2", scopeId, UploadStatus.OPEN.name)
    fun listStaleOpen(cutoff: Instant): List<BlobUpload> =
        list("status = ?1 and createdAt < ?2", UploadStatus.OPEN.name, cutoff)
}

@ApplicationScoped
class BlobChunkRepository : PanacheRepositoryBase<BlobChunk, String> {
    fun listByUploadId(uploadId: String): List<BlobChunk> = list("uploadId = ?1 order by chunkIndex", uploadId)
    fun findByUpload(uploadId: String, chunkIndex: Int): BlobChunk? =
        find("uploadId = ?1 and chunkIndex = ?2", uploadId, chunkIndex).firstResult()
    fun findByBlob(blobId: String, chunkIndex: Int): BlobChunk? =
        find("blobId = ?1 and chunkIndex = ?2", blobId, chunkIndex).firstResult()
    fun deleteByUploadId(uploadId: String): Long = delete("uploadId", uploadId)
}

@ApplicationScoped
class MediaRepository : PanacheRepositoryBase<Media, String> {
    fun listByNodeId(nodeId: String): List<Media> = list("nodeId = ?1 order by createdAt", nodeId)
}

@ApplicationScoped
class MediaDerivativeRepository : PanacheRepositoryBase<MediaDerivative, String> {
    fun listByMediaId(mediaId: String): List<MediaDerivative> = list("mediaId", mediaId)
}
