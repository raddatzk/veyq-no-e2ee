package com.veyq.app.shared.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// Encrypted media attached to a Memory/Chapter node. Bytes + thumbnail are opaque ciphertext (sealed under
// the scope content key); the server stores only ciphertext + low-sensitivity metadata (node, mime, size,
// takenAt for ordering). It never interprets the media.

@Entity
@Table(name = "veyq_media")
class Media {
    @Id var id: String = ""
    @Column(name = "scope_id", nullable = false) var scopeId: String = ""
    @Column(name = "node_id", nullable = false) var nodeId: String = ""
    @Column(name = "taken_at", nullable = false) var takenAt: Instant = Instant.EPOCH
    @Column(name = "mime_type", nullable = false) var mimeType: String = ""
    @Column(name = "byte_size", nullable = false) var byteSize: Long = 0
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "sealed_blob", nullable = false) var sealedBlob: ByteArray = ByteArray(0)
    @Column(name = "sealed_thumb") var sealedThumb: ByteArray? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class MediaRepository : PanacheRepositoryBase<Media, String> {
    /** A node's media, ordered by takenAt (the gallery order — no separate manual sort field). */
    fun listByNodeId(nodeId: String): List<Media> = list("nodeId = ?1 order by takenAt, createdAt", nodeId)
}
