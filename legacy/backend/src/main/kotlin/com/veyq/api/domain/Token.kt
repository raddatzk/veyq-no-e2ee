package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant
import java.time.temporal.ChronoUnit

abstract class AbstractTokenRepo<T : AbstractTokenEntity> : PanacheRepository<T> {
    fun findByTokenHash(hash: String): T? =
        find("tokenHash", hash).firstResult()

    fun findActiveByTokenHash(hash: String, now: Instant = Instant.now()): T? =
        find("tokenHash = ?1 and revokedAt is null and expiresAt > ?2", hash, now).firstResult()
}

@MappedSuperclass
abstract class AbstractTokenEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "token_hash", nullable = false, unique = true)
    lateinit var tokenHash: String

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    lateinit var user: User

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
@Table(name = "veyq_access_token")
class AccessToken : AbstractTokenEntity() {
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "refresh_token_id", nullable = false)
    lateinit var refreshToken: RefreshToken
}

@ApplicationScoped
class AccessTokenRepo : AbstractTokenRepo<AccessToken>() {
    fun deleteExpiredToken(interval: Long) {
        delete("revokedAt < ?1 or expiresAt < ?1", Instant.now().minus(interval, ChronoUnit.DAYS))
    }
}

@Entity
@Table(name = "veyq_refresh_token")
class RefreshToken : AbstractTokenEntity() {
    @OneToMany(mappedBy = "refreshToken", fetch = FetchType.LAZY)
    var accessTokens: MutableSet<AccessToken> = mutableSetOf()

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rotated_to")
    var rotatedTo: RefreshToken? = null
}

@ApplicationScoped
class RefreshTokenRepo : AbstractTokenRepo<RefreshToken>() {
    fun deleteExpiredToken(interval: Long) {
        delete("revokedAt < ?1 or expiresAt < ?1", Instant.now().minus(interval, ChronoUnit.DAYS))
    }
}

@Entity
@Table(name = "veyq_mobile_session_code")
class MobileSessionCode : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    lateinit var codeHash: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "redirect_uri", nullable = false, length = 1024)
    lateinit var redirectUri: String

    @Column(name = "state", length = 512)
    var state: String? = null

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class MobileSessionCodeRepo : PanacheRepository<MobileSessionCode> {
    fun findByCodeHashForUpdate(codeHash: String): MobileSessionCode? =
        find("codeHash", codeHash)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult()
}
