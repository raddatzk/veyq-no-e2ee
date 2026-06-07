package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "veyq_memory")
class Memory : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_memory_id")
    var parentMemory: Memory? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    var parentFolder: TenantFolder? = null

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    lateinit var owner: User

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    lateinit var scopeType: LibraryScopeType

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_group_id")
    var scopeGroup: TenantGroup? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scope_user_id")
    var scopeUser: User? = null

    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var displayName: String

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_media_id")
    var coverMedia: Media? = null

    @Column(name = "cover_storage_key")
    var coverStorageKey: String? = null

    @Column(name = "cover_mime_type")
    var coverMimeType: String? = null

    @Column(name = "cover_original_filename")
    var coverOriginalFilename: String? = null

    @Column(name = "cover_crop_x")
    var coverCropX: Double? = null

    @Column(name = "cover_crop_y")
    var coverCropY: Double? = null

    @Column(name = "cover_crop_width")
    var coverCropWidth: Double? = null

    @Column(name = "cover_crop_height")
    var coverCropHeight: Double? = null

    @Column(name = "start_date", nullable = false)
    lateinit var startDate: LocalDate

    @Column(name = "end_date", nullable = false)
    lateinit var endDate: LocalDate

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @OneToMany(mappedBy = "parentMemory", fetch = FetchType.LAZY, cascade = [CascadeType.REMOVE])
    var childMemories: MutableSet<Memory> = mutableSetOf()

    @PrePersist
    fun beforePersist() {
        if (!::publicId.isInitialized) {
            publicId = NanoIdUtils.randomNanoId(16)
        }
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun beforeUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(
    name = "veyq_memory_favorite",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_veyq_memory_favorite_user_memory",
            columnNames = ["tenant_id", "user_id", "memory_id"],
        ),
    ],
)
class MemoryFavorite : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id", nullable = false)
    lateinit var memory: Memory

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun beforePersist() {
        createdAt = Instant.now()
    }
}

@Entity
@Table(
    name = "veyq_memory_participant",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_veyq_memory_participant_memory_user",
            columnNames = ["memory_id", "user_id"],
        ),
    ],
)
class MemoryParticipant : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id", nullable = false)
    lateinit var memory: Memory

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_user_id", nullable = false)
    lateinit var addedBy: User

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun beforePersist() {
        createdAt = Instant.now()
    }
}

@ApplicationScoped
class MemoryRepo : PanacheRepository<Memory> {
    @Inject lateinit var queryEntityManager: EntityManager

    fun findByTenantAndPublicId(tenant: Tenant, publicId: String): Memory? =
        find("tenant = ?1 and publicId = ?2", tenant, publicId).firstResult()

    fun listRoots(tenant: Tenant): List<Memory> =
        list("tenant = ?1 and parentMemory is null and parentFolder is null order by displayName", tenant)

    fun listByFolder(tenant: Tenant, parentFolder: TenantFolder?): List<Memory> =
        if (parentFolder == null) {
            list("tenant = ?1 and parentMemory is null and parentFolder is null order by displayName", tenant)
        } else {
            list("tenant = ?1 and parentMemory is null and parentFolder = ?2 order by displayName", tenant, parentFolder)
        }

    fun listByParentFolders(tenant: Tenant, parentFolders: Collection<TenantFolder>): List<Memory> {
        if (parentFolders.isEmpty()) return emptyList()
        return list("tenant = ?1 and parentMemory is null and parentFolder in ?2 order by displayName", tenant, parentFolders)
    }

    fun listChildren(tenant: Tenant, parent: Memory): List<Memory> =
        list("tenant = ?1 and parentMemory = ?2 order by displayName", tenant, parent)

    @Suppress("UNCHECKED_CAST")
    fun listDescendantsAndSelf(tenant: Tenant, root: Memory): List<Memory> =
        queryEntityManager.createNativeQuery(
            """
            with recursive memory_tree(id, depth) as (
                select id, 0
                from veyq.veyq_memory
                where tenant_id = :tenantId and id = :rootId
                union all
                select child.id, memory_tree.depth + 1
                from veyq.veyq_memory child
                join memory_tree on child.parent_memory_id = memory_tree.id
                where child.tenant_id = :tenantId
            )
            select m.*
            from veyq.veyq_memory m
            join memory_tree on memory_tree.id = m.id
            order by memory_tree.depth, m.displayName, m.id
            """.trimIndent(),
            Memory::class.java,
        )
            .setParameter("tenantId", tenant.id)
            .setParameter("rootId", root.id)
            .resultList as List<Memory>

    fun countChildrenByParentIds(tenant: Tenant, parentIds: Collection<Long>): Map<Long, Int> {
        if (parentIds.isEmpty()) return emptyMap()
        return queryEntityManager.createQuery(
            """
            select m.parentMemory.id, count(m)
            from Memory m
            where m.tenant = :tenant and m.parentMemory.id in :parentIds
            group by m.parentMemory.id
            """.trimIndent(),
            Array<Any>::class.java,
        )
            .setParameter("tenant", tenant)
            .setParameter("parentIds", parentIds)
            .resultList
            .associate { row -> row[0] as Long to (row[1] as Long).toInt() }
    }
}

@ApplicationScoped
class MemoryFavoriteRepo : PanacheRepository<MemoryFavorite> {
    fun findByTenantUserAndMemory(tenant: Tenant, user: User, memory: Memory): MemoryFavorite? =
        find("tenant = ?1 and user = ?2 and memory = ?3", tenant, user, memory).firstResult()

    fun listByTenantAndUser(tenant: Tenant, user: User): List<MemoryFavorite> =
        list("tenant = ?1 and user = ?2 order by createdAt desc", tenant, user)

    fun favoriteMemoryIds(tenant: Tenant, user: User, memories: Collection<Memory>): Set<Long> {
        if (memories.isEmpty()) return emptySet()
        return list("tenant = ?1 and user = ?2 and memory in ?3", tenant, user, memories)
            .map { it.memory.id }
            .toSet()
    }
}

@ApplicationScoped
class MemoryParticipantRepo : PanacheRepository<MemoryParticipant> {
    fun findByMemoryAndUser(memory: Memory, user: User): MemoryParticipant? =
        find("memory = ?1 and user = ?2", memory, user).firstResult()

    fun listByMemory(memory: Memory): List<MemoryParticipant> =
        list("memory = ?1 order by user.displayName, user.permalink", memory)

    fun listByTenantAndUser(tenant: Tenant, user: User): List<MemoryParticipant> =
        list("tenant = ?1 and user = ?2 order by memory.displayName", tenant, user)
}
