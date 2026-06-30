package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

enum class TenantRole {
    TenantOwner,
    TenantMember
}

enum class TenantMemberStatus {
    Pending,
    Active,
    Removed,
}

@Entity
@Table(name = "veyq_tenant")
class Tenant : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(nullable = false, unique = true)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var displayName: String

    @Column(nullable = false, unique = true)
    lateinit var domain: String

    @Column(name = "activated_at")
    var activatedAt: Instant? = null

    @Column(name = "logo_storage_key", length = 512)
    var logoStorageKey: String? = null

    @Column(name = "logo_content_type", length = 64)
    var logoContentType: String? = null

    @Column(name = "logo_updated_at")
    var logoUpdatedAt: Instant? = null

    @Column(name = "terms_markdown_by_locale", columnDefinition = "text")
    var termsMarkdownByLocale: String? = null

    @Column(name = "terms_version", nullable = false)
    var termsVersion: Int = 0

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    var members: MutableSet<TenantMember> = mutableSetOf()

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    var groups: MutableSet<TenantGroup> = mutableSetOf()
}

@ApplicationScoped
class TenantRepo : PanacheRepository<Tenant> {
    fun findByName(name: String): Tenant? =
        find("name", name).firstResult()

    fun findByDomain(domain: String): Tenant? =
        find("domain", domain).firstResult()

    fun listAllOrderByDomain(): List<Tenant> =
        list("order by domain")

    fun existsByName(name: String): Boolean =
        count("name", name) > 0

    fun existsByDomain(domain: String): Boolean =
        count("domain", domain) > 0

    fun existsAny(): Boolean =
        count() > 0

    fun listInactive(): List<Tenant> =
        list("activatedAt is null")

    fun deleteByIdValue(id: Long): Long =
        delete("id", id)
}

@Entity
@Table(name = "veyq_tenant_member")
class TenantMember : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: TenantMemberId

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("tenantId")
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: TenantRole

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var status: TenantMemberStatus

    @Column(name = "removed_at")
    var removedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by_user_id")
    var removedBy: User? = null

    @PrePersist
    fun assignStatus() {
        if (!::status.isInitialized) {
            status = if (acceptedAt == null) TenantMemberStatus.Pending else TenantMemberStatus.Active
        }
    }
}

@Embeddable
data class TenantMemberId(
    @Column(name = "tenant_id")
    var tenantId: Long = 0,
    @Column(name = "user_id")
    var userId: Long = 0,
) : java.io.Serializable

@ApplicationScoped
class TenantMemberRepo : PanacheRepository<TenantMember> {
    fun findByTenantAndUser(tenant: Tenant, user: User): TenantMember? =
        find("tenant = ?1 and user = ?2", tenant, user).firstResult()

    fun listByUser(user: User): List<TenantMember> =
        list("user", user)

    fun listByTenant(tenant: Tenant): List<TenantMember> =
        list("tenant", tenant)

    fun countActiveTenantOwners(tenant: Tenant): Long =
        count(
            "tenant = ?1 and role = ?2 and status = ?3 and user.disabledAt is null and user.deletedAt is null",
            tenant,
            TenantRole.TenantOwner,
            TenantMemberStatus.Active,
        )

    fun deleteByTenant(tenant: Tenant): Long =
        delete("tenant", tenant)
}
