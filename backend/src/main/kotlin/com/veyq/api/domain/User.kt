package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import jakarta.persistence.LockModeType
import java.time.Instant

enum class ServerRole {
    ServerOwner,
    ServerMember,
}

@Entity
@Table(name = "veyq_user")
class User : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "password_hash", nullable = false)
    lateinit var passwordHash: String

    @Column(name = "display_name", nullable = false)
    lateinit var displayName: String

    @Column(name = "permalink", nullable = false, unique = true, length = 120)
    lateinit var permalink: String

    @Column(name = "email", nullable = false, unique = true)
    lateinit var email: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: ServerRole

    @Column(name = "password_set_at")
    var passwordSetAt: Instant? = null

    @Column(name = "avatar_storage_key", length = 512)
    var avatarStorageKey: String? = null

    @Column(name = "avatar_content_type", length = 64)
    var avatarContentType: String? = null

    @Column(name = "avatar_updated_at")
    var avatarUpdatedAt: Instant? = null

    @Column(name = "disabled_at")
    var disabledAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disabled_by_user_id")
    var disabledBy: User? = null

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_replacement_user_id")
    var deletedReplacementUser: User? = null

    @Column(name = "system_account", nullable = false)
    var systemUser: Boolean = false

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var tenantMemberships: MutableSet<TenantMember> = mutableSetOf()

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var passkeyCredentials: MutableSet<PasskeyCredential> = mutableSetOf()

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    var totpCredential: TotpCredential? = null
}

@ApplicationScoped
class UserRepo : PanacheRepository<User> {
    fun findByEmail(email: String): User? {
        return find("email", email).firstResult()
    }

    fun findByPermalink(permalink: String): User? =
        find("permalink", permalink).firstResult()

    fun listAllOrderByEmail(): List<User> =
        list("order by email")

    fun searchByNameOrEmail(query: String): List<User> {
        val pattern = "%${query.lowercase()}%"
        return list("lower(displayName) like ?1 or lower(email) like ?1 order by displayName, email", pattern)
    }

    fun countActiveByRole(role: ServerRole): Long =
        count("role = ?1 and passwordSetAt is not null and disabledAt is null and deletedAt is null and systemUser = false", role)

    fun listByRole(role: ServerRole): List<User> =
        list("role", role)

    fun deletedUserReplacement(): User? =
        find("systemUser = true and permalink = ?1", "deleted-user").firstResult()

    fun countNonSystemUsers(): Long =
        count("systemUser = false")
}

@Entity
@Table(name = "veyq_passkey_credential")
class PasskeyCredential : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "credential_id", nullable = false, unique = true, length = 512)
    lateinit var credentialId: String

    @Lob
    @Column(name = "public_key", nullable = false)
    lateinit var publicKey: ByteArray

    @Column(name = "public_key_algorithm", nullable = false)
    var publicKeyAlgorithm: Long = 0

    @Column(nullable = false)
    var counter: Long = 0

    @Column(nullable = false, length = 36)
    lateinit var aaguid: String

    @Column
    var transports: String? = null

    @Column(name = "display_name", nullable = false)
    lateinit var displayName: String

    @Column(name = "relying_party_id")
    var relyingPartyId: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
}

@ApplicationScoped
class PasskeyCredentialRepo : PanacheRepository<PasskeyCredential> {
    fun findByCredentialId(credentialId: String): PasskeyCredential? =
        find("credentialId", credentialId).firstResult()

    fun listByUser(user: User): List<PasskeyCredential> =
        list("user = ?1 order by createdAt desc", user)

    fun listByUserId(userId: Long): List<PasskeyCredential> =
        list("user.id = ?1 order by createdAt desc", userId)

    fun countByUser(user: User): Long =
        count("user = ?1", user)

    fun countByUserAndRelyingPartyId(user: User, relyingPartyId: String): Long =
        count("user = ?1 and relyingPartyId = ?2", user, relyingPartyId)

    fun listByUserAndRelyingPartyId(user: User, relyingPartyId: String): List<PasskeyCredential> =
        list("user = ?1 and relyingPartyId = ?2 order by createdAt desc", user, relyingPartyId)

    fun findByIdAndUser(id: Long, user: User): PasskeyCredential? =
        find("id = ?1 and user = ?2", id, user).firstResult()
}

@Entity
@Table(name = "veyq_totp_credential")
class TotpCredential : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    lateinit var user: User

    @Column(name = "secret", nullable = false, length = 64)
    lateinit var secret: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "enabled_at", nullable = false)
    lateinit var enabledAt: Instant

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null

    @Column(name = "last_used_step")
    var lastUsedStep: Long? = null
}

@ApplicationScoped
class TotpCredentialRepo : PanacheRepository<TotpCredential> {
    fun findByUser(user: User): TotpCredential? =
        find("user = ?1", user).firstResult()

    fun findByUserId(userId: Long): TotpCredential? =
        find("user.id = ?1", userId).firstResult()
}

@Entity
@Table(name = "veyq_passkey_link_token")
class PasskeyLinkToken : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    lateinit var codeHash: String

    @Column(name = "grant_hash", unique = true, length = 64)
    var grantHash: String? = null

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "auth_domain", nullable = false)
    lateinit var authDomain: String

    @Column(name = "target_domain", nullable = false)
    lateinit var targetDomain: String

    @Column(name = "redirect_path", nullable = false, length = 1024)
    lateinit var redirectPath: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "exchanged_at")
    var exchangedAt: Instant? = null

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class PasskeyLinkTokenRepo : PanacheRepository<PasskeyLinkToken> {
    fun findByCodeHash(codeHash: String): PasskeyLinkToken? =
        find("codeHash", codeHash).firstResult()

    fun findByCodeHashForUpdate(codeHash: String): PasskeyLinkToken? =
        find("codeHash", codeHash)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult()

    fun findByGrantHash(grantHash: String): PasskeyLinkToken? =
        find("grantHash", grantHash).firstResult()

    fun findByGrantHashForUpdate(grantHash: String): PasskeyLinkToken? =
        find("grantHash", grantHash)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult()
}

@Entity
@Table(name = "veyq_reset_password_token")
class ResetPasswordToken : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    lateinit var user: User

    @Column(name = "reset_domain")
    var resetDomain: String? = null

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

    fun findByTokenHashForUpdate(tokenHash: String): ResetPasswordToken? =
        find("tokenHash", tokenHash)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult()

    fun listOpenByUser(user: User): List<ResetPasswordToken> =
        list("user = ?1 and acceptedAt is null and revokedAt is null", user)

    fun deleteInvalidBefore(cutoff: Instant): Long =
        delete("expiresAt < ?1 or acceptedAt < ?1 or revokedAt < ?1", cutoff)
}
