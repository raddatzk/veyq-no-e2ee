package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "veyq_tenant_registration_invite")
class TenantRegistrationInvite : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    lateinit var createdBy: User

    @Column(name = "token_hash", nullable = false, unique = true)
    lateinit var tokenHash: String

    @Column(name = "token", nullable = false, unique = true)
    lateinit var token: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "max_registrations")
    var maxRegistrations: Int? = null

    @Column(name = "registration_count", nullable = false)
    var registrationCount: Int = 0

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class TenantRegistrationInviteRepo : PanacheRepository<TenantRegistrationInvite> {
    fun findByTokenHash(tokenHash: String): TenantRegistrationInvite? =
        find("tokenHash", tokenHash).firstResult()

    fun findByTokenHashForUpdate(tokenHash: String): TenantRegistrationInvite? =
        find("tokenHash", tokenHash).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()

    fun findByTenantAndId(tenant: Tenant, id: Long): TenantRegistrationInvite? =
        find("tenant = ?1 and id = ?2", tenant, id).firstResult()

    fun listByTenant(tenant: Tenant): List<TenantRegistrationInvite> =
        list("tenant = ?1 order by createdAt desc, id desc", tenant)

    fun deleteByTenant(tenant: Tenant): Long =
        delete("tenant", tenant)
}
