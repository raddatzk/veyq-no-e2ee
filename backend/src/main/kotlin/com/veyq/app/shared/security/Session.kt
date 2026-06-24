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
    @Column(name = "refresh_token_hash", unique = true) var refreshTokenHash: String? = null
    @Column(name = "user_id", nullable = false) var userId: String = ""
    @Column(name = "device_ref") var deviceRef: String? = null
    @Column(name = "device_name", columnDefinition = "TEXT") var deviceName: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.EPOCH
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant = Instant.EPOCH
    @Column(name = "refresh_expires_at") var refreshExpiresAt: Instant? = null
}

@ApplicationScoped
class SessionRepository : PanacheRepositoryBase<Session, String> {
    fun findByTokenHash(tokenHash: String): Session? = find("tokenHash", tokenHash).firstResult()
    fun findByRefreshTokenHash(refreshTokenHash: String): Session? = find("refreshTokenHash", refreshTokenHash).firstResult()
    fun listDevicesByUserId(userId: String, now: Instant): List<Session> =
        list("userId = ?1 and deviceRef is not null and (expiresAt >= ?2 or refreshExpiresAt >= ?2)", userId, now)
    fun deleteByUserId(userId: String): Long = delete("userId", userId)
    fun updateDeviceName(userId: String, deviceRef: String, encryptedDeviceName: String): Int =
        update("deviceName = ?1 where userId = ?2 and deviceRef = ?3", encryptedDeviceName, userId, deviceRef)
    fun deleteByUserIdAndDeviceRef(userId: String, deviceRef: String): Long =
        delete("userId = ?1 and deviceRef = ?2", userId, deviceRef)
    fun deleteByTokenHash(tokenHash: String): Long = delete("tokenHash", tokenHash)
    fun deleteByRefreshTokenHash(refreshTokenHash: String): Long = delete("refreshTokenHash", refreshTokenHash)
    fun deleteExpired(now: Instant): Long = delete("expiresAt < ?1 and (refreshExpiresAt is null or refreshExpiresAt < ?1)", now)
}

data class IssuedSession(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val refreshMaxAgeSeconds: Long,
    val deviceRef: String,
)

@ApplicationScoped
class SessionService(private val sessions: SessionRepository, config: com.veyq.app.shared.config.SessionConfig) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val accessTtl: Duration = config.accessTtl()
    private val refreshTtl: Duration = config.refreshTtl()

    /** SHA-256(token), base64url — what we persist and look up by. The raw token never touches the DB. */
    private fun hash(token: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    private fun randomToken(): String {
        val raw = ByteArray(32).also { rng.nextBytes(it) }
        return b64.encodeToString(raw)
    }

    @Transactional
    fun issuePair(userId: String, deviceRef: String? = null): IssuedSession {
        val accessToken = randomToken()
        val refreshToken = randomToken()
        val resolvedDeviceRef = deviceRef?.trim()?.takeIf { it.isNotBlank() } ?: randomToken()
        val now = Instant.now()
        val refreshExpiresAt = now.plus(refreshTtl)
        sessions.persist(
            Session().apply {
                id = UUID.randomUUID().toString()
                this.tokenHash = hash(accessToken)
                this.refreshTokenHash = hash(refreshToken)
                this.userId = userId
                this.deviceRef = resolvedDeviceRef
                createdAt = now
                expiresAt = now.plus(accessTtl)
                this.refreshExpiresAt = refreshExpiresAt
            },
        )
        return IssuedSession(accessToken, refreshToken, refreshExpiresAt, refreshTtl.seconds, resolvedDeviceRef)
    }

    @Transactional
    fun issue(userId: String): String = issuePair(userId).accessToken

    @Transactional
    fun refresh(refreshToken: String?): IssuedSession? {
        val token = refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val session = sessions.findByRefreshTokenHash(hash(token)) ?: return null
        val now = Instant.now()
        if ((session.refreshExpiresAt ?: Instant.EPOCH).isBefore(now)) {
            sessions.delete(session)
            return null
        }
        val nextAccessToken = randomToken()
        val nextRefreshToken = randomToken()
        val nextRefreshExpiresAt = now.plus(refreshTtl)
        session.tokenHash = hash(nextAccessToken)
        session.refreshTokenHash = hash(nextRefreshToken)
        session.expiresAt = now.plus(accessTtl)
        session.refreshExpiresAt = nextRefreshExpiresAt
        return IssuedSession(nextAccessToken, nextRefreshToken, nextRefreshExpiresAt, refreshTtl.seconds, session.deviceRef ?: randomToken())
    }

    /** Invalidates every session for a user (instant token revocation). Returns how many were dropped. */
    @Transactional
    fun invalidateAll(userId: String): Long = sessions.deleteByUserId(userId)

    @Transactional
    fun listDevices(userId: String): List<Session> = sessions.listDevicesByUserId(userId, Instant.now())

    @Transactional
    fun renameDevice(userId: String, deviceRef: String, encryptedDeviceName: String): Int =
        sessions.updateDeviceName(userId, deviceRef, encryptedDeviceName)

    @Transactional
    fun invalidateDevice(userId: String, deviceRef: String): Long = sessions.deleteByUserIdAndDeviceRef(userId, deviceRef)

    /** Drop every expired session row (the scheduled GC sweep). Returns how many were purged. */
    @Transactional
    fun purgeExpired(): Long = sessions.deleteExpired(Instant.now())

    /** Logout: drop the whole access/refresh family for either the bearer token or its refresh cookie. */
    @Transactional
    fun invalidate(authorizationHeader: String?, refreshToken: String? = null): Boolean {
        val accessToken = bearer(authorizationHeader)
        if (accessToken != null && sessions.deleteByTokenHash(hash(accessToken)) > 0) return true
        val refresh = refreshToken?.takeIf { it.isNotBlank() }
        if (refresh != null && sessions.deleteByRefreshTokenHash(hash(refresh)) > 0) return true
        return false
    }

    /** Resolves a `Bearer <token>` header to a user id, or null if missing/unknown/expired. */
    @Transactional
    fun resolve(authorizationHeader: String?): String? {
        val token = bearer(authorizationHeader) ?: return null
        val tokenHash = hash(token)
        val session = sessions.findByTokenHash(tokenHash) ?: return null
        if (session.expiresAt.isBefore(Instant.now())) {
            return null
        }
        return session.userId
    }

    private fun bearer(authorizationHeader: String?): String? =
        authorizationHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
}
