package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*

enum class GroupRole {
    GroupOwner,
    GroupMember
}

@Entity
@Table(
    name = "lunva_tenant_group",
    uniqueConstraints = [UniqueConstraint(name = "uq_lunva_tenant_group_name", columnNames = ["tenant_id", "name"])]
)
class TenantGroup : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var displayName: String

    @OneToMany(mappedBy = "group", fetch = FetchType.LAZY)
    var members: MutableSet<TenantGroupMember> = mutableSetOf()
}

@ApplicationScoped
class TenantGroupRepo : PanacheRepository<TenantGroup> {
    fun listByTenant(tenant: Tenant): List<TenantGroup> =
        list("tenant", tenant)

    fun findByTenantAndName(tenant: Tenant, name: String): TenantGroup? =
        find("tenant = ?1 and name = ?2", tenant, name).firstResult()
}

@Entity
@Table(name = "lunva_tenant_group_member")
class TenantGroupMember : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: TenantGroupMemberId

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("groupId")
    @JoinColumn(name = "group_id", nullable = false)
    lateinit var group: TenantGroup

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: GroupRole
}

@Embeddable
data class TenantGroupMemberId(
    @Column(name = "group_id")
    var groupId: Long = 0,
    @Column(name = "user_id")
    var userId: Long = 0,
) : java.io.Serializable

@ApplicationScoped
class TenantGroupMemberRepo : PanacheRepository<TenantGroupMember> {
    fun findByGroupAndUser(group: TenantGroup, user: User): TenantGroupMember? =
        find("group = ?1 and user = ?2", group, user).firstResult()
}
