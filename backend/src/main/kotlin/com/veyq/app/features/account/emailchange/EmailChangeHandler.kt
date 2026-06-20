package com.veyq.app.features.account.emailchange

import com.veyq.app.generated.model.EmailChangeConfirmRequest
import com.veyq.app.generated.model.EmailChangeRequestRequest
import com.veyq.app.generated.model.UserSummary
import com.veyq.app.generated.model.ServerRole as ApiServerRole
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.config.AppConfig
import com.veyq.app.shared.config.EmailChangeConfig
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.account.change-email — change the account login email. The email is NOT part of the key derivation
 * (the KEK is Argon2id over Secret Key + password + a random salt), so this is a pure identifier swap — no
 * re-wrap of any key material. `request` (authenticated) emails a one-time token to the NEW address; `confirm`
 * (token-only, like password reset: control of the new inbox is the proof) consumes it and swaps the email.
 */
@ApplicationScoped
class EmailChangeHandler(
    private val users: UserRepository,
    private val tokens: EmailChangeRepository,
    private val sessions: SessionService,
    private val mailer: Mailer,
    app: AppConfig,
    emailChangeCfg: EmailChangeConfig,
) {
    private val appBaseUrl: String = app.baseUrl()
    private val ttl: Duration = emailChangeCfg.ttl()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val rng = SecureRandom()

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    @Transactional
    fun requestChange(auth: String?, req: EmailChangeRequestRequest) {
        val user = requireUser(auth)
        val newEmail = req.newEmail.trim()
        // The new address must be free (this also rejects a no-op change to the caller's own current email).
        if (users.findByEmail(newEmail) != null) throw emailTaken()

        tokens.deleteByUserId(user.id) // one active token per user
        val raw = enc.encodeToString(ByteArray(32).also(rng::nextBytes))
        val now = Instant.now()
        tokens.persist(
            EmailChangeToken().apply {
                id = UUID.randomUUID().toString()
                userId = user.id
                this.newEmail = newEmail
                tokenSha256 = sha256(raw)
                createdAt = now
                expiresAt = now.plus(ttl)
            },
        )
        val link = "${appBaseUrl.trimEnd('/')}/#confirm-email=$raw"
        val body = buildString {
            append("Hi,\n\nA request was made to change your veyq account email to this address.\n")
            append("Open this link to confirm the change:\n\n")
            append(link).append("\n\nThe link expires soon. If you didn't request this, you can ignore this email.\n")
        }
        mailer.send(Mail.withText(newEmail, "Confirm your new veyq email", body))
    }

    @Transactional
    fun confirm(req: EmailChangeConfirmRequest): UserSummary {
        val token = validToken(req.token)
        val user = users.findById(token.userId) ?: throw invalidToken()
        // Re-check the address is still free (another account may have claimed it since the request).
        val holder = users.findByEmail(token.newEmail)
        if (holder != null && holder.id != user.id) throw emailTaken()
        user.email = token.newEmail
        token.confirmedAt = Instant.now()
        return user.toSummary()
    }

    private fun requireUser(auth: String?): User {
        val userId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Authentication required")
        return users.findById(userId) ?: throw ApiException(401, "UNAUTHORIZED", "Authentication required")
    }

    private fun validToken(raw: String): EmailChangeToken {
        val t = tokens.findByTokenSha256(sha256(raw)) ?: throw invalidToken()
        if (t.confirmedAt != null || t.expiresAt.isBefore(Instant.now())) throw invalidToken()
        return t
    }

    private fun User.toSummary(): UserSummary =
        UserSummary(id = id, email = email, displayName = displayName, serverRole = ApiServerRole.decode(serverRole.name))

    private fun emailTaken() = ApiException(409, "EMAIL_TAKEN", "That email address is already in use")
    private fun invalidToken() = ApiException(400, "INVALID_TOKEN", "Invalid or expired email-change token")
}
