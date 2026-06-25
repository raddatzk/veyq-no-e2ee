package com.veyq.app.features.account.resetpassword

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A one-time password-reset token. Only the token's sha256 is stored (the raw token rides the email link).
 * Presenting the token proves email control — the email second factor of recovery; the Secret Key is the
 * crypto factor that actually decrypts the RECOVERY master-key wrap client-side.
 */
@Entity
@Table(name = "veyq_password_reset")
class PasswordResetToken {
    @Id var id: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "token_sha256", nullable = false, unique = true) var tokenSha256: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
    @Column(name = "consumed_at") var consumedAt: Instant? = null
}

@ApplicationScoped
class PasswordResetRepository : PanacheRepositoryBase<PasswordResetToken, String> {
    fun findByTokenSha256(hash: ByteArray): PasswordResetToken? = find("tokenSha256", hash).firstResult()
    fun deleteByUserId(userId: String): Long = delete("userId", userId)
    fun deleteExpired(now: Instant): Long = delete("expiresAt < ?1", now)
}
