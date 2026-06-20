package com.veyq.app.features.account.emailchange

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A one-time email-change token. Only the token's sha256 is stored (the raw token rides the email link to the
 * NEW address). Presenting it proves control of the new inbox — the sole proof required, because the address
 * change does not touch any key material (email is not part of the KEK derivation).
 */
@Entity
@Table(name = "veyq_email_change_token")
class EmailChangeToken {
    @Id var id: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "new_email", nullable = false) var newEmail: String = ""
    @Column(name = "token_sha256", nullable = false, unique = true) var tokenSha256: ByteArray = ByteArray(0)
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
    @Column(name = "confirmed_at") var confirmedAt: Instant? = null
}

@ApplicationScoped
class EmailChangeRepository : PanacheRepositoryBase<EmailChangeToken, String> {
    fun findByTokenSha256(hash: ByteArray): EmailChangeToken? = find("tokenSha256", hash).firstResult()
    fun deleteByUserId(userId: String): Long = delete("userId", userId)
    fun deleteExpired(now: Instant): Long = delete("expiresAt < ?1", now)
}
