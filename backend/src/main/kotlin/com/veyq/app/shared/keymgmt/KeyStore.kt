package com.veyq.app.shared.keymgmt

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.time.Instant

// The blind key store. Every byte column holds opaque ciphertext + a suite id; never interpreted.

@Entity
@Table(name = "veyq_identity_key")
class IdentityKey {
    @Id var id: String = ""
    @Column(name = "user_id", nullable = false, unique = true) var userId: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "kem_public_key", nullable = false) var kemPublicKey: ByteArray = ByteArray(0)
    @Column(name = "sign_public_key", nullable = false) var signPublicKey: ByteArray = ByteArray(0)
    @Column(name = "wrapped_private_key", nullable = false) var wrappedPrivateKey: ByteArray = ByteArray(0)
    @Column(name = "wrapped_private_suite_id", nullable = false) var wrappedPrivateSuiteId: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_key_blob")
class KeyBlob {
    @Id var id: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "unlock_method", nullable = false) var unlockMethod: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(name = "wrapped_key", nullable = false) var wrappedKey: ByteArray = ByteArray(0)
    @Column(name = "kdf_params") var kdfParams: ByteArray? = null
    @Column(name = "credential_ref") var credentialRef: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "veyq_auth_verifier")
class AuthVerifier {
    @Id var id: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(nullable = false) var kind: String = ""
    @Column(name = "suite_id", nullable = false) var suiteId: String = ""
    @Column(nullable = false) var verifier: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class IdentityKeyRepository : PanacheRepositoryBase<IdentityKey, String> {
    fun findByUserId(userId: String): IdentityKey? = find("userId", userId).firstResult()
}

@ApplicationScoped
class KeyBlobRepository : PanacheRepositoryBase<KeyBlob, String> {
    fun listByUserId(userId: String): List<KeyBlob> = list("userId", userId)
    fun findByUserIdAndUnlockMethod(userId: String, unlockMethod: String): List<KeyBlob> =
        list("userId = ?1 and unlockMethod = ?2", userId, unlockMethod)
    fun countByUserId(userId: String): Long = count("userId", userId)

    /** Row-locked snapshot of a user's wraps: serializes concurrent removals so the lockout floor holds. */
    fun listByUserIdForUpdate(userId: String): List<KeyBlob> =
        find("userId", userId).withLock(LockModeType.PESSIMISTIC_WRITE).list()
}

@ApplicationScoped
class AuthVerifierRepository : PanacheRepositoryBase<AuthVerifier, String> {
    fun findByUserId(userId: String): AuthVerifier? = find("userId", userId).firstResult()
}
