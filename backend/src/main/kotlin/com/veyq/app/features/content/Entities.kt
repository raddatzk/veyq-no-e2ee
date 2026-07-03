package com.veyq.app.features.content

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A node in a scope's encrypted Memory tree (en.memory.tree, cr-0027). FOLDER organizes only; MEMORY is the
 * content unit (its `wrappedContentKey` = K_mem under K_SC); CHAPTER sits inside a Memory. Operator-blind:
 * `encryptedName` / `wrappedContentKey` are opaque ciphertext, `slugToken` a blinded keyed MAC. `parentId`
 * is the **parent slot** — the parent node id, or the scope id for a root-level node (so slug uniqueness per
 * parent works at the root too); it is not a single-table FK.
 */
@Entity
@Table(name = "veyq_node")
class NodeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "parent_id", nullable = false, length = 36)
    lateinit var parentId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "encrypted_name", nullable = false)
    lateinit var encryptedName: String

    @Column(name = "slug_token", nullable = false, length = 128)
    lateinit var slugToken: String

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    @Column(name = "wrapped_content_key")
    var wrappedContentKey: String? = null

    @Column(name = "wrapped_meta")
    var wrappedMeta: String? = null

    /** The node's standalone **cover** blob id (cr-0073): a veyq_blob with NO veyq_media row (never in the gallery), or null. Opaque — the server never learns it is "the cover" (that stays in the sealed `wrappedMeta.cover`). */
    @Column(name = "cover_blob_id", length = 36)
    var coverBlobId: String? = null

    /** The cover blob's ciphertext size in bytes (cr-0073), for the byte budget + retention; null when no cover blob. */
    @Column(name = "cover_byte_size")
    var coverByteSize: Long? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<NodeEntity, String> {
        const val FOLDER = "FOLDER"
        const val MEMORY = "MEMORY"
        const val CHAPTER = "CHAPTER"
    }
}
