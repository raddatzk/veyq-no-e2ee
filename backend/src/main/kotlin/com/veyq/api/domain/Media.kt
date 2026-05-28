package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "veyq_media_blob",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_veyq_media_blob_tenant_sha256", columnNames = ["tenant_id", "content_sha256"]),
    ],
)
class MediaBlob : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    lateinit var contentSha256: String

    @Column(name = "storage_key", nullable = false, length = 512)
    lateinit var storageKey: String

    @Column(name = "preview_storage_key", length = 512)
    var previewStorageKey: String? = null

    @Column(name = "thumbnail_storage_key", length = 512)
    var thumbnailStorageKey: String? = null

    @Column(name = "mime_type", nullable = false)
    lateinit var mimeType: String

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0

    @Column(name = "captured_at")
    var capturedAt: Instant? = null

    @Column(name = "camera_make")
    var cameraMake: String? = null

    @Column(name = "camera_model")
    var cameraModel: String? = null

    @Column(name = "lens_model")
    var lensModel: String? = null

    @Column(name = "image_width")
    var imageWidth: Int? = null

    @Column(name = "image_height")
    var imageHeight: Int? = null

    @Column(name = "gps_latitude")
    var gpsLatitude: Double? = null

    @Column(name = "gps_longitude")
    var gpsLongitude: Double? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @PrePersist
    fun assignTimestamps() {
        val now = Instant.now()
        if (!::createdAt.isInitialized) createdAt = now
        if (!::updatedAt.isInitialized) updatedAt = now
    }

    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "veyq_media")
class Media : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    lateinit var scopeType: LibraryScopeType

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_group_id")
    var scopeGroup: TenantGroup? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_user_id")
    var scopeUser: User? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id")
    var memory: Memory? = null

    @Column(nullable = false)
    lateinit var title: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "media_blob_id", nullable = false)
    lateinit var blob: MediaBlob

    @Column(name = "original_filename")
    var originalFilename: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_user_id")
    var uploaderUser: User? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_identity_id")
    var shareIdentity: MemoryShareIdentity? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "captured_at")
    var capturedAt: Instant? = null

    @Column(name = "captured_at_override", nullable = false)
    var capturedAtOverride: Boolean = false

    @PrePersist
    fun assignPublicId() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        if (!::createdAt.isInitialized) {
            createdAt = Instant.now()
        }
    }
}

@ApplicationScoped
class MediaBlobRepo : PanacheRepository<MediaBlob> {
    fun findByTenantAndSha256(tenant: Tenant, sha256: String): MediaBlob? =
        find("tenant = ?1 and contentSha256 = ?2", tenant, sha256).firstResult()
}

data class ArchiveMediaRow(
    val id: Long,
    val publicId: String,
    val storageKey: String,
    val originalFilename: String?,
    val title: String,
    val mimeType: String,
    val sortTime: Instant,
    val createdAt: Instant,
    val memoryId: Long,
)

data class ArchiveMediaKeyset(
    val sortTime: Instant,
    val createdAt: Instant,
    val id: Long,
)

@ApplicationScoped
class MediaRepo : PanacheRepository<Media> {
    @Inject lateinit var archiveEntityManager: EntityManager

    fun findByTenantAndPublicId(tenant: Tenant, publicId: String): Media? =
        find("tenant = ?1 and publicId = ?2", tenant, publicId).firstResult()

    fun listByMemory(memory: Memory): List<Media> =
        list("memory = ?1 order by coalesce(capturedAt, createdAt), createdAt", memory)

    fun listByMemoriesOrdered(memories: List<Memory>): List<Media> {
        if (memories.isEmpty()) return emptyList()
        return list("memory in ?1 order by coalesce(capturedAt, createdAt), createdAt", memories)
    }

    fun listArchiveRowsByMemoryIds(memoryIds: Collection<Long>, after: ArchiveMediaKeyset?, limit: Int): List<ArchiveMediaRow> {
        if (memoryIds.isEmpty() || limit <= 0) return emptyList()

        val keysetFilter = if (after == null) {
            ""
        } else {
            """
            and (
                coalesce(m.capturedAt, m.createdAt) > :lastSortTime
                or (coalesce(m.capturedAt, m.createdAt) = :lastSortTime and m.createdAt > :lastCreatedAt)
                or (coalesce(m.capturedAt, m.createdAt) = :lastSortTime and m.createdAt = :lastCreatedAt and m.id > :lastId)
            )
            """.trimIndent()
        }

        val query = archiveEntityManager.createQuery(
            """
            select new com.veyq.api.domain.ArchiveMediaRow(
                m.id,
                m.publicId,
                m.blob.storageKey,
                m.originalFilename,
                m.title,
                m.blob.mimeType,
                coalesce(m.capturedAt, m.createdAt),
                m.createdAt,
                m.memory.id
            )
            from Media m
            where m.memory.id in :memoryIds
            $keysetFilter
            order by coalesce(m.capturedAt, m.createdAt), m.createdAt, m.id
            """.trimIndent(),
            ArchiveMediaRow::class.java,
        )
        query.setParameter("memoryIds", memoryIds)
        if (after != null) {
            query.setParameter("lastSortTime", after.sortTime)
            query.setParameter("lastCreatedAt", after.createdAt)
            query.setParameter("lastId", after.id)
        }
        query.maxResults = limit
        return query.resultList.also { archiveEntityManager.clear() }
    }
}
