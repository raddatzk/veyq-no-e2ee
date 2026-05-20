package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "veyq_user_invitation")
class UserInvitation : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @Column(name = "token_hash", nullable = false, unique = true)
    lateinit var tokenHash: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class UserInvitationRepo : PanacheRepository<UserInvitation> {
    fun findByTokenHash(tokenHash: String): UserInvitation? =
        find("tokenHash", tokenHash).firstResult()

    fun listOpenByUser(user: User): List<UserInvitation> =
        list("user = ?1 and acceptedAt is null and revokedAt is null", user)

    fun listOpenByUserAndTenant(user: User, tenant: Tenant): List<UserInvitation> =
        list("user = ?1 and tenant = ?2 and acceptedAt is null and revokedAt is null", user, tenant)

    fun deleteInvalidBefore(cutoff: Instant): Long =
        delete("expiresAt < ?1 or acceptedAt < ?1 or revokedAt < ?1", cutoff)
}
