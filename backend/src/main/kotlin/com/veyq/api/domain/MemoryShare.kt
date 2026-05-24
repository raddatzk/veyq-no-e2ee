package com.veyq.api.domain

import com.soundicly.jnanoidenhanced.jnanoid.NanoIdUtils
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class MemoryShareRole {
    VIEWER,
    CONTRIBUTOR,
    MANAGER,
    UPLOADER,
}

@Entity
@Table(name = "veyq_memory_share")
class MemoryShare : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "public_id", nullable = false, unique = true, length = 16, updatable = false)
    lateinit var publicId: String

    @Column(name = "name", length = 120)
    var name: String? = null

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    lateinit var tenant: Tenant

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "root_memory_id", nullable = false)
    lateinit var rootMemory: Memory

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: MemoryShareRole

    @Column(name = "password_hash")
    var passwordHash: String? = null

    @Column(nullable = false)
    var enabled: Boolean = true

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    lateinit var createdBy: User

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @OneToMany(mappedBy = "share", fetch = FetchType.LAZY, cascade = [CascadeType.REMOVE])
    var identities: MutableSet<MemoryShareIdentity> = mutableSetOf()

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
@Table(name = "veyq_memory_share_identity")
class MemoryShareIdentity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "share_id", nullable = false)
    lateinit var share: MemoryShare

    @Column(name = "display_name", nullable = false)
    lateinit var displayName: String

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @PrePersist
    fun beforePersist() {
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
@Table(name = "veyq_memory_share_access_token")
class MemoryShareAccessToken : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "token_hash", nullable = false, unique = true)
    lateinit var tokenHash: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "share_identity_id", nullable = false)
    lateinit var shareIdentity: MemoryShareIdentity

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "refresh_token_id", nullable = false)
    lateinit var refreshToken: MemoryShareRefreshToken

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "last_accessed_at", nullable = false)
    lateinit var lastAccessedAt: Instant
}

@Entity
@Table(name = "veyq_memory_share_refresh_token")
class MemoryShareRefreshToken : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "token_hash", nullable = false, unique = true)
    lateinit var tokenHash: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "share_identity_id", nullable = false)
    lateinit var shareIdentity: MemoryShareIdentity

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "last_accessed_at", nullable = false)
    lateinit var lastAccessedAt: Instant

    @OneToMany(mappedBy = "refreshToken", fetch = FetchType.LAZY)
    var accessTokens: MutableSet<MemoryShareAccessToken> = mutableSetOf()

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rotated_to")
    var rotatedTo: MemoryShareRefreshToken? = null
}

@ApplicationScoped
class MemoryShareRepo : PanacheRepository<MemoryShare> {
    fun findByTenantAndPublicId(tenant: Tenant, publicId: String): MemoryShare? =
        find("tenant = ?1 and publicId = ?2", tenant, publicId).firstResult()

    fun findByPublicId(publicId: String): MemoryShare? =
        find("publicId", publicId).firstResult()

    fun listByMemory(memory: Memory): List<MemoryShare> =
        list("rootMemory = ?1 order by createdAt desc", memory)
}

@ApplicationScoped
class MemoryShareIdentityRepo : PanacheRepository<MemoryShareIdentity>

@ApplicationScoped
class MemoryShareAccessTokenRepo : PanacheRepository<MemoryShareAccessToken> {
    fun findActiveByTokenHash(hash: String, now: Instant = Instant.now()): MemoryShareAccessToken? =
        find("tokenHash = ?1 and revokedAt is null and expiresAt > ?2", hash, now).firstResult()

    fun findByTokenHash(hash: String): MemoryShareAccessToken? =
        find("tokenHash", hash).firstResult()

    fun deleteExpiredToken(interval: Long) {
        delete("revokedAt < ?1 or expiresAt < ?1", Instant.now().minus(interval, ChronoUnit.DAYS))
    }
}

@ApplicationScoped
class MemoryShareRefreshTokenRepo : PanacheRepository<MemoryShareRefreshToken> {
    fun findActiveByTokenHash(hash: String, now: Instant = Instant.now()): MemoryShareRefreshToken? =
        find("tokenHash = ?1 and revokedAt is null and expiresAt > ?2", hash, now).firstResult()

    fun findByTokenHash(hash: String): MemoryShareRefreshToken? =
        find("tokenHash", hash).firstResult()

    fun deleteExpiredToken(interval: Long) {
        delete("revokedAt < ?1 or expiresAt < ?1", Instant.now().minus(interval, ChronoUnit.DAYS))
    }
}
