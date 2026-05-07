package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

enum class ServerRole {
    ServerOwner,
    ServerMember,
}

@Entity
@Table(name = "lunva_user")
class User : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "password_hash", nullable = false)
    lateinit var passwordHash: String

    @Column(name = "display_name", nullable = false)
    lateinit var displayName: String

    @Column(name = "email", nullable = false, unique = true)
    lateinit var email: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: ServerRole

    @Column(name = "password_set_at")
    var passwordSetAt: Instant? = null

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var tenantMemberships: MutableSet<TenantMember> = mutableSetOf()
}

@ApplicationScoped
class UserRepo : PanacheRepository<User> {
    fun findByEmail(email: String): User? {
        return find("email", email).firstResult()
    }

    fun listAllOrderByEmail(): List<User> =
        list("order by email")

    fun searchByNameOrEmail(query: String): List<User> {
        val pattern = "%${query.lowercase()}%"
        return list("lower(displayName) like ?1 or lower(email) like ?1 order by displayName, email", pattern)
    }

    fun countActiveByRole(role: ServerRole): Long =
        count("role = ?1 and passwordSetAt is not null", role)
}

@Entity
@Table(name = "lunva_reset_password_token")
class ResetPasswordToken : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

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
class ResetPasswordTokenRepo : PanacheRepository<ResetPasswordToken> {
    fun findByTokenHash(tokenHash: String): ResetPasswordToken? =
        find("tokenHash", tokenHash).firstResult()

    fun listOpenByUser(user: User): List<ResetPasswordToken> =
        list("user = ?1 and acceptedAt is null and revokedAt is null", user)

    fun deleteInvalidBefore(cutoff: Instant): Long =
        delete("expiresAt < ?1 or acceptedAt < ?1 or revokedAt < ?1", cutoff)
}
