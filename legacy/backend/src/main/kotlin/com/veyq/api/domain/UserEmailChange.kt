package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "veyq_user_email_change")
class UserEmailChange : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "pending_email", nullable = false)
    lateinit var pendingEmail: String

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
class UserEmailChangeRepo : PanacheRepository<UserEmailChange> {
    fun findByTokenHash(tokenHash: String): UserEmailChange? =
        find("tokenHash", tokenHash).firstResult()

    fun listOpenByUser(user: User): List<UserEmailChange> =
        list("user = ?1 and acceptedAt is null and revokedAt is null", user)

    fun findLatestOpenByUser(user: User): UserEmailChange? =
        find("user = ?1 and acceptedAt is null and revokedAt is null order by createdAt desc", user).firstResult()

    fun deleteInvalidBefore(cutoff: Instant): Long =
        delete("expiresAt < ?1 or acceptedAt < ?1 or revokedAt < ?1", cutoff)
}
