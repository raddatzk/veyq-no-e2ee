package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*

enum class TenantRole {
    TENANT_OWNER,
    TENANT_MEMBER
}

@Entity
@Table(name = "lunva_tenant")
class Tenant : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(nullable = false, unique = true)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var displayName: String

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    var members: MutableSet<TenantMember> = mutableSetOf()

    @OneToMany(mappedBy = "tenant", fetch = FetchType.LAZY)
    var groups: MutableSet<TenantGroup> = mutableSetOf()
}

@ApplicationScoped
class TenantRepo : PanacheRepository<Tenant> {
    fun findByName(name: String): Tenant? =
        find("name", name).firstResult()

    fun existsByName(name: String): Boolean =
        count("name", name) > 0

    fun existsAny(): Boolean =
        count() > 0
}

@Entity
@Table(name = "lunva_tenant_member")
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
}
