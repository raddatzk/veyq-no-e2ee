package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// Resumable upload state. Session = the in-progress (or completed) chunked media; chunk = one received AEAD
// frame, pointing at the BlobStore (the bytes never live in the DB). Used for resume (which indices landed)
// and download (the frame manifest).

@Entity
@Table(name = "veyq_upload_session")
class UploadSession {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "total_chunks", nullable = false) var totalChunks: Int = 0
    @Column(name = "mime_type", nullable = false) var mimeType: String = ""
    @Column(name = "byte_size", nullable = false) var byteSize: Long = 0
    @Column(name = "taken_at", nullable = false) var takenAt: Instant = Instant.EPOCH
    @Column(nullable = false) var status: String = ""
    @Column(name = "created_by", nullable = false) var createdBy: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_media_chunk")
class MediaChunk {
    @Id var id: String = ""
    @Column(name = "upload_id", nullable = false) var uploadId: String = ""
    @Column(name = "chunk_index", nullable = false) var chunkIndex: Int = 0
    @Column(name = "object_key", nullable = false) var objectKey: String = ""
    @Column(name = "byte_size", nullable = false) var byteSize: Long = 0
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

enum class UploadStatus { OPEN, COMPLETED }

@ApplicationScoped
class UploadSessionRepository : PanacheRepositoryBase<UploadSession, String> {
    /** OPEN sessions started before the cutoff — abandoned uploads the GC sweep reclaims. */
    fun listStaleOpen(cutoff: java.time.Instant): List<UploadSession> =
        list("status = ?1 and createdAt < ?2", UploadStatus.OPEN.name, cutoff)
}

@ApplicationScoped
class MediaChunkRepository : PanacheRepositoryBase<MediaChunk, String> {
    fun listByUploadId(uploadId: String): List<MediaChunk> = list("uploadId = ?1 order by chunkIndex", uploadId)
    fun find(uploadId: String, chunkIndex: Int): MediaChunk? =
        find("uploadId = ?1 and chunkIndex = ?2", uploadId, chunkIndex).firstResult()
    fun deleteByUploadId(uploadId: String): Long = delete("uploadId", uploadId)
}
