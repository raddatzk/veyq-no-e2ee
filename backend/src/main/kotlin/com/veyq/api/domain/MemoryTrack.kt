package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.*
import java.time.Instant

enum class MemoryTrackActivityType {
    HIKING,
    WALKING,
    RUNNING,
    CYCLING,
    MOUNTAIN_BIKING,
    ROAD_CYCLING,
    GRAVEL_CYCLING,
    SKIING,
    WATER,
    OTHER,
}

@Entity
@Table(name = "veyq_memory_track")
class MemoryTrack : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id", nullable = false)
    lateinit var memory: Memory

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    lateinit var scopeType: LibraryScopeType

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_group_id")
    var scopeGroup: TenantGroup? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_user_id")
    var scopeUser: User? = null

    @Column(nullable = false, length = 160)
    lateinit var title: String

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 64)
    lateinit var activityType: MemoryTrackActivityType

    @Column(name = "original_filename", nullable = false, length = 255)
    lateinit var originalFilename: String

    @Column(name = "storage_key", nullable = false, length = 512)
    lateinit var storageKey: String

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0

    @Column(name = "distance_meters", nullable = false)
    var distanceMeters: Long = 0

    @Column(name = "ascent_meters")
    var ascentMeters: Long? = null

    @Column(name = "descent_meters")
    var descentMeters: Long? = null

    @Column(name = "point_count", nullable = false)
    var pointCount: Int = 0

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "ended_at")
    var endedAt: Instant? = null

    @Column(name = "min_latitude", nullable = false)
    var minLatitude: Double = 0.0

    @Column(name = "min_longitude", nullable = false)
    var minLongitude: Double = 0.0

    @Column(name = "max_latitude", nullable = false)
    var maxLatitude: Double = 0.0

    @Column(name = "max_longitude", nullable = false)
    var maxLongitude: Double = 0.0

    @Column(name = "geometry_json", nullable = false, columnDefinition = "mediumtext")
    lateinit var geometryJson: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @PrePersist
    fun beforePersist() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        val now = Instant.now()
        if (!::createdAt.isInitialized) createdAt = now
        if (!::updatedAt.isInitialized) updatedAt = now
    }

    @PreUpdate
    fun beforeUpdate() {
        updatedAt = Instant.now()
    }
}

data class ArchiveTrackRow(
    val id: Long,
    val publicId: String,
    val storageKey: String,
    val originalFilename: String,
    val title: String,
    val sortTime: Instant,
    val createdAt: Instant,
    val memoryId: Long,
)

data class ArchiveTrackKeyset(
    val sortTime: Instant,
    val createdAt: Instant,
    val id: Long,
)

@ApplicationScoped
class MemoryTrackRepo : PanacheRepository<MemoryTrack> {
    @Inject lateinit var archiveEntityManager: EntityManager

    fun findByTenantAndPublicId(tenant: Tenant, publicId: String): MemoryTrack? =
        find("tenant = ?1 and publicId = ?2", tenant, publicId).firstResult()

    fun listByMemory(memory: Memory): List<MemoryTrack> =
        list("memory = ?1 order by coalesce(startedAt, createdAt), createdAt, id", memory)

    fun listByMemoriesOrdered(memories: List<Memory>): List<MemoryTrack> {
        if (memories.isEmpty()) return emptyList()
        return list("memory in ?1 order by coalesce(startedAt, createdAt), createdAt, id", memories)
    }

    fun listArchiveRowsByMemoryIds(memoryIds: Collection<Long>, after: ArchiveTrackKeyset?, limit: Int): List<ArchiveTrackRow> {
        if (memoryIds.isEmpty() || limit <= 0) return emptyList()

        val keysetFilter = if (after == null) {
            ""
        } else {
            """
            and (
                coalesce(t.startedAt, t.createdAt) > :lastSortTime
                or (coalesce(t.startedAt, t.createdAt) = :lastSortTime and t.createdAt > :lastCreatedAt)
                or (coalesce(t.startedAt, t.createdAt) = :lastSortTime and t.createdAt = :lastCreatedAt and t.id > :lastId)
            )
            """.trimIndent()
        }

        val query = archiveEntityManager.createQuery(
            """
            select new com.veyq.api.domain.ArchiveTrackRow(
                t.id,
                t.publicId,
                t.storageKey,
                t.originalFilename,
                t.title,
                coalesce(t.startedAt, t.createdAt),
                t.createdAt,
                t.memory.id
            )
            from MemoryTrack t
            where t.memory.id in :memoryIds
            $keysetFilter
            order by coalesce(t.startedAt, t.createdAt), t.createdAt, t.id
            """.trimIndent(),
            ArchiveTrackRow::class.java,
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
