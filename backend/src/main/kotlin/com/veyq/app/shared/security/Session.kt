package com.veyq.app.shared.security

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** An opaque bearer session. The token proves identity to the server; it unlocks no content. */
@Entity
@Table(name = "veyq_session")
class Session {
    @Id var id: String = ""
    @Column(nullable = false, unique = true) var token: String = ""
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
}

@ApplicationScoped
class SessionRepository : PanacheRepositoryBase<Session, String> {
    fun findByToken(token: String): Session? = find("token", token).firstResult()
    fun deleteByUserId(userId: String): Long = delete("userId", userId)
}

@ApplicationScoped
class SessionService(private val sessions: SessionRepository) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val ttl: Duration = Duration.ofDays(30)

    @Transactional
    fun issue(userId: String): String {
        val raw = ByteArray(32).also { rng.nextBytes(it) }
        val token = b64.encodeToString(raw)
        val now = Instant.now()
        sessions.persist(
            Session().apply {
                id = UUID.randomUUID().toString()
                this.token = token
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

    /** Resolves a `Bearer <token>` header to a user id, or null if missing/unknown/expired. */
    @Transactional
    fun resolve(authorizationHeader: String?): String? {
        val token = authorizationHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val session = sessions.findByToken(token) ?: return null
        if (session.expiresAt.isBefore(Instant.now())) return null
        return session.userId
    }
}
