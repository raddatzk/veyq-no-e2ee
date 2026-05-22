package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

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

@ApplicationScoped
class MemoryRepo : PanacheRepository<Memory> {
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

    fun listChildren(tenant: Tenant, parent: Memory): List<Memory> =
        list("tenant = ?1 and parentMemory = ?2 order by displayName", tenant, parent)
}
