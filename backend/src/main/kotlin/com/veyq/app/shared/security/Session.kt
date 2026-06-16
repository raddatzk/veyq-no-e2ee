package com.veyq.app.shared.security

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * An opaque bearer session. The token proves identity to the server; it unlocks no content.
 *
 * Only the SHA-256 of the bearer token is stored (`token_hash`), never the raw token: a DB dump then
 * yields no usable bearer credential (the raw token is high-entropy random, so the hash is not reversible).
 * The client holds the raw token; the server hashes every presented token and looks it up by hash.
 */
@Entity
@Table(name = "veyq_session")
class Session {
    @Id var id: String = ""
    @Column(name = "token_hash", nullable = false, unique = true) var tokenHash: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class SessionRepository : PanacheRepositoryBase<Session, String> {
    fun findByTokenHash(tokenHash: String): Session? = find("tokenHash", tokenHash).firstResult()
    fun deleteByUserId(userId: String): Long = delete("userId", userId)
    fun deleteByTokenHash(tokenHash: String): Long = delete("tokenHash", tokenHash)
    fun deleteExpired(now: Instant): Long = delete("expiresAt < ?1", now)
}

@ApplicationScoped
class SessionService(private val sessions: SessionRepository) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val ttl: Duration = Duration.ofDays(30)

    /** SHA-256(token), base64url — what we persist and look up by. The raw token never touches the DB. */
    private fun hash(token: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    @Transactional
    fun issue(userId: String): String {
        val raw = ByteArray(32).also { rng.nextBytes(it) }
        val token = b64.encodeToString(raw)
        val now = Instant.now()
        sessions.persist(
            Session().apply {
                id = UUID.randomUUID().toString()
                this.tokenHash = hash(token)
                this.userId = userId
                createdAt = now
                expiresAt = now.plus(ttl)
            },
        )
        return token
    }

    /** Invalidates every session for a user (instant token revocation). Returns how many were dropped. */
    @Transactional
    fun invalidateAll(userId: String): Long = sessions.deleteByUserId(userId)

    /** Drop every expired session row (the scheduled GC sweep). Returns how many were purged. */
    @Transactional
    fun purgeExpired(): Long = sessions.deleteExpired(Instant.now())

    /** Logout: drop the session row for a `Bearer <token>` header. Idempotent (unknown token is a no-op). */
    @Transactional
    fun invalidate(authorizationHeader: String?) {
        val token = bearer(authorizationHeader) ?: return
        sessions.deleteByTokenHash(hash(token))
    }

    /** Resolves a `Bearer <token>` header to a user id, or null if missing/unknown/expired. */
    @Transactional
    fun resolve(authorizationHeader: String?): String? {
        val token = bearer(authorizationHeader) ?: return null
        val tokenHash = hash(token)
        val session = sessions.findByTokenHash(tokenHash) ?: return null
        if (session.expiresAt.isBefore(Instant.now())) {
            sessions.deleteByTokenHash(tokenHash) // lazy GC: an expired token purges itself on next use
            return null
        }
        return session.userId
    }

    private fun bearer(authorizationHeader: String?): String? =
        authorizationHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
}
