package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.*
import java.time.Instant

enum class LibraryScopeType {
    Tenant,
    Group,
    User
}

@Entity
@Table(name = "veyq_tenant_folder")
class TenantFolder : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

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

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @OneToMany(mappedBy = "parentFolder", fetch = FetchType.LAZY, cascade = [CascadeType.REMOVE])
    var childFolders: MutableSet<TenantFolder> = mutableSetOf()

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
class TenantFolderRepo : PanacheRepository<TenantFolder> {
    @Inject lateinit var queryEntityManager: EntityManager

    fun listByTenant(tenant: Tenant): List<TenantFolder> = list("tenant", tenant)
    fun listChildren(tenant: Tenant, parent: TenantFolder?): List<TenantFolder> =
        if (parent == null) list("tenant = ?1 and parentFolder is null order by displayName", tenant)
        else list("tenant = ?1 and parentFolder = ?2 order by displayName", tenant, parent)

    @Suppress("UNCHECKED_CAST")
    fun listDescendantsAndSelf(tenant: Tenant, root: TenantFolder): List<TenantFolder> =
        queryEntityManager.createNativeQuery(
            """
            with recursive folder_tree(id, depth) as (
                select id, 0
                from veyq.veyq_tenant_folder
                where tenant_id = :tenantId and id = :rootId
                union all
                select child.id, folder_tree.depth + 1
                from veyq.veyq_tenant_folder child
                join folder_tree on child.parent_folder_id = folder_tree.id
                where child.tenant_id = :tenantId
            )
            select f.*
            from veyq.veyq_tenant_folder f
            join folder_tree on folder_tree.id = f.id
            order by folder_tree.depth, f.displayName, f.id
            """.trimIndent(),
            TenantFolder::class.java,
        )
            .setParameter("tenantId", tenant.id)
            .setParameter("rootId", root.id)
            .resultList as List<TenantFolder>
}
