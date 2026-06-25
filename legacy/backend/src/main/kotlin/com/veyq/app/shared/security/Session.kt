package com.veyq.app.shared.security

import com.veyq.app.shared.config.SessionConfig
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

const val LOGIN_METHOD_PASSWORD = "PASSWORD"
const val SESSION_REVOKED_LOGOUT = "LOGOUT"
const val SESSION_REVOKED_ROTATED = "ROTATED"
const val SESSION_REVOKED_REUSE_DETECTED = "REUSE_DETECTED"
const val SESSION_REVOKED_REMOTE = "REMOTE_LOGOUT"
const val SESSION_REVOKED_ALL = "REVOKE_ALL"

/**
 * An opaque bearer session version. One login creates a session family (`root_session_id`); each refresh
 * appends a new version and marks the previous one rotated so reuse can be detected.
 *
 * Only SHA-256 token hashes are stored, never raw tokens. The session proves server identity only; it
 * unlocks no content and carries no key material.
 */
@Entity
@Table(name = "veyq_session")
class Session {
    @Id var id: String = ""
    @Column(name = "root_session_id") var rootSessionId: String = ""
    @Column(name = "parent_session_id") var parentSessionId: String? = null
    @Column(name = "token_hash", nullable = false, unique = true) var tokenHash: String = ""
    @Column(name = "refresh_token_hash", unique = true) var refreshTokenHash: String? = null
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "encrypted_label", columnDefinition = "TEXT") var encryptedLabel: String? = null
    @Column(name = "login_method") var loginMethod: String = LOGIN_METHOD_PASSWORD
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
    @Column(name = "refresh_expires_at") var refreshExpiresAt: Instant? = null
    @Column(name = "revoked_at") var revokedAt: Instant? = null
    @Column(name = "revoked_reason") var revokedReason: String? = null
}

data class IssuedSession(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val refreshMaxAgeSeconds: Long,
    val rootSessionId: String,
)

data class LoginSessionFamily(
    val rootSessionId: String,
    val encryptedLabel: String,
    val loginMethod: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,
)

@ApplicationScoped
class SessionRepository : PanacheRepositoryBase<Session, String> {
    fun findByTokenHash(tokenHash: String): Session? = find("tokenHash", tokenHash).firstResult()
    fun findByRefreshTokenHash(refreshTokenHash: String): Session? = find("refreshTokenHash", refreshTokenHash).firstResult()
    fun listByUserId(userId: String): List<Session> = list("userId", userId)
    fun updateFamilyLabel(userId: String, rootSessionId: String, encryptedLabel: String): Int =
        update("encryptedLabel = ?1 where userId = ?2 and rootSessionId = ?3", encryptedLabel, userId, rootSessionId)
    fun revokeByUserId(userId: String, revokedAt: Instant, reason: String): Long =
        update("revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null", revokedAt, reason, userId).toLong()
    fun revokeFamily(userId: String, rootSessionId: String, revokedAt: Instant, reason: String): Long =
        update(
            "revokedAt = ?1, revokedReason = ?2 where userId = ?3 and rootSessionId = ?4 and revokedAt is null",
            revokedAt,
            reason,
            userId,
            rootSessionId,
        ).toLong()
    fun flagFamilyReuseDetected(userId: String, rootSessionId: String, revokedAt: Instant): Long =
        update(
            "revokedAt = ?1, revokedReason = ?2 where userId = ?3 and rootSessionId = ?4",
            revokedAt,
            SESSION_REVOKED_REUSE_DETECTED,
            userId,
            rootSessionId,
        ).toLong()
    fun deleteExpired(cutoff: Instant): Long =
        delete(
            "(revokedAt is not null and revokedAt < ?1) or " +
                "(revokedAt is null and refreshExpiresAt is not null and refreshExpiresAt < ?1) or " +
                "(revokedAt is null and refreshExpiresAt is null and expiresAt < ?1)",
            cutoff,
        )
}

@ApplicationScoped
class SessionService(private val sessions: SessionRepository, config: SessionConfig) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val accessTtl: Duration = config.accessTtl()
    private val refreshTtl: Duration = config.refreshTtl()
    private val auditRetention: Duration = config.auditRetention()

    /** SHA-256(token), base64url — what we persist and look up by. The raw token never touches the DB. */
    private fun hash(token: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    private fun randomToken(): String {
        val raw = ByteArray(32).also { rng.nextBytes(it) }
        return b64.encodeToString(raw)
    }

    @Transactional
    fun issuePair(userId: String, loginMethod: String = LOGIN_METHOD_PASSWORD): IssuedSession {
        val accessToken = randomToken()
        val refreshToken = randomToken()
        val rootSessionId = UUID.randomUUID().toString()
        val now = Instant.now()
        val refreshExpiresAt = now.plus(refreshTtl)
        sessions.persist(
            Session().apply {
                id = rootSessionId
                this.rootSessionId = rootSessionId
                this.tokenHash = hash(accessToken)
                this.refreshTokenHash = hash(refreshToken)
                this.userId = userId
                this.loginMethod = loginMethod
                createdAt = now
                expiresAt = now.plus(accessTtl)
                this.refreshExpiresAt = refreshExpiresAt
            },
        )
        return IssuedSession(accessToken, refreshToken, refreshExpiresAt, refreshTtl.seconds, rootSessionId)
    }

    @Transactional
    fun issue(userId: String): String = issuePair(userId).accessToken

    @Transactional
    fun refresh(refreshToken: String?): IssuedSession? {
        val token = refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val previous = sessions.findByRefreshTokenHash(hash(token)) ?: return null
        val now = Instant.now()
        val rootSessionId = previous.rootSessionId.ifBlank { previous.id }
        if (previous.revokedAt != null) {
            if (previous.revokedReason == SESSION_REVOKED_ROTATED) {
                sessions.flagFamilyReuseDetected(previous.userId, rootSessionId, now)
            }
            return null
        }
        if ((previous.refreshExpiresAt ?: Instant.EPOCH).isBefore(now)) {
            return null
        }

        val nextAccessToken = randomToken()
        val nextRefreshToken = randomToken()
        val nextRefreshExpiresAt = now.plus(refreshTtl)
        previous.revokedAt = now
        previous.revokedReason = SESSION_REVOKED_ROTATED
        sessions.persist(
            Session().apply {
                id = UUID.randomUUID().toString()
                this.rootSessionId = rootSessionId
                parentSessionId = previous.id
                tokenHash = hash(nextAccessToken)
                refreshTokenHash = hash(nextRefreshToken)
                userId = previous.userId
                encryptedLabel = previous.encryptedLabel
                loginMethod = previous.loginMethod
                createdAt = now
                expiresAt = now.plus(accessTtl)
                refreshExpiresAt = nextRefreshExpiresAt
            },
        )
        return IssuedSession(nextAccessToken, nextRefreshToken, nextRefreshExpiresAt, refreshTtl.seconds, rootSessionId)
    }

    /** Revokes every active session for a user. Returns how many rows were newly revoked. */
    @Transactional
    fun invalidateAll(userId: String, reason: String = SESSION_REVOKED_ALL): Long =
        sessions.revokeByUserId(userId, Instant.now(), reason)

    @Transactional
    fun listLoginSessions(userId: String): List<LoginSessionFamily> {
        val now = Instant.now()
        return sessions.listByUserId(userId)
            .groupBy { it.rootSessionId.ifBlank { it.id } }
            .values
            .mapNotNull { family ->
                val active = family.filter { it.revokedAt == null && ((it.refreshExpiresAt ?: it.expiresAt).isAfter(now)) }
                if (active.isEmpty()) return@mapNotNull null
                val root = family.minBy { it.createdAt }
                val latest = active.maxBy { it.createdAt }
                LoginSessionFamily(
                    rootSessionId = root.rootSessionId.ifBlank { root.id },
                    encryptedLabel = latest.encryptedLabel ?: "",
                    loginMethod = root.loginMethod,
                    createdAt = root.createdAt,
                    lastUsedAt = latest.createdAt,
                )
            }
            .sortedByDescending { it.lastUsedAt }
    }

    @Transactional
    fun renameLoginSession(userId: String, rootSessionId: String, encryptedLabel: String): Int =
        sessions.updateFamilyLabel(userId, rootSessionId, encryptedLabel)

    @Transactional
    fun invalidateLoginSession(userId: String, rootSessionId: String, reason: String = SESSION_REVOKED_REMOTE): Long =
        sessions.revokeFamily(userId, rootSessionId, Instant.now(), reason)

    /** Drop audit-retained rows after the retention window. Returns how many were purged. */
    @Transactional
    fun purgeExpired(): Long = sessions.deleteExpired(Instant.now().minus(auditRetention))

    /** Logout: revoke the whole access/refresh family for either the bearer token or its refresh cookie. */
    @Transactional
    fun invalidate(authorizationHeader: String?, refreshToken: String? = null): Boolean {
        val now = Instant.now()
        val accessSession = bearer(authorizationHeader)?.let { sessions.findByTokenHash(hash(it)) }
        if (accessSession != null) {
            sessions.revokeFamily(accessSession.userId, accessSession.rootSessionId.ifBlank { accessSession.id }, now, SESSION_REVOKED_LOGOUT)
            return true
        }
        val refreshSession = refreshToken?.takeIf { it.isNotBlank() }?.let { sessions.findByRefreshTokenHash(hash(it)) }
        if (refreshSession != null) {
            sessions.revokeFamily(refreshSession.userId, refreshSession.rootSessionId.ifBlank { refreshSession.id }, now, SESSION_REVOKED_LOGOUT)
            return true
        }
        return false
    }

    /** Resolves a `Bearer <token>` header to a user id, or null if missing/unknown/expired/revoked. */
    @Transactional
    fun resolve(authorizationHeader: String?): String? {
        val token = bearer(authorizationHeader) ?: return null
        val session = sessions.findByTokenHash(hash(token)) ?: return null
        if (session.revokedAt != null || session.expiresAt.isBefore(Instant.now())) {
            return null
        }
        return session.userId
    }

    private fun bearer(authorizationHeader: String?): String? =
        authorizationHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
}
