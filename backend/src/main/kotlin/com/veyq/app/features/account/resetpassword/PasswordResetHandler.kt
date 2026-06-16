package com.veyq.app.features.account.resetpassword

import com.veyq.app.generated.model.AuthVerifier as AuthVerifierDto
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.generated.model.PasswordResetConfirmRequest
import com.veyq.app.generated.model.PasswordResetMaterial
import com.veyq.app.generated.model.PasswordResetRequestRequest
import com.veyq.app.generated.model.PasswordResetTokenRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.AuthVerifierRepository
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.security.SessionService
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * uc.account.reset-password — recover a forgotten password with the Secret Key (Emergency Kit) AND email
 * access. `request` emails a one-time token; `material` (token-gated) releases the RECOVERY master-key wrap
 * so the client can unwrap the master key with the Secret Key; `confirm` (token-gated) swaps the new PASSWORD
 * wrap + auth verifier the client built under the new password. The server sees neither secret.
 */
@ApplicationScoped
class PasswordResetHandler(
    private val users: UserRepository,
    private val keyBlobs: KeyBlobRepository,
    private val authVerifiers: AuthVerifierRepository,
    private val tokens: PasswordResetRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
    private val mailer: Mailer,
    @param:ConfigProperty(name = "veyq.app.base-url") private val appBaseUrl: String,
    @param:ConfigProperty(name = "veyq.password-reset.ttl", defaultValue = "PT1H") private val ttl: Duration,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val rng = SecureRandom()

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    @Transactional
    fun requestReset(req: PasswordResetRequestRequest) {
        val email = req.email.trim()
        val user = users.findByEmail(email) ?: return // never reveal whether an account exists
        // No RECOVERY wrap → recovery was never possible; stay silent (still 204).
        if (keyBlobs.findByUserIdAndUnlockMethod(user.id, "RECOVERY").isEmpty()) return

        tokens.deleteByUserId(user.id) // one active token per user
        val raw = enc.encodeToString(ByteArray(32).also(rng::nextBytes))
        val now = Instant.now()
        tokens.persist(
            PasswordResetToken().apply {
                id = UUID.randomUUID().toString()
                userId = user.id
                tokenSha256 = sha256(raw)
                createdAt = now
                expiresAt = now.plus(ttl)
            },
        )
        val link = "${appBaseUrl.trimEnd('/')}/#reset=$raw"
        val body = buildString {
            append("Hi,\n\nA password reset was requested for your veyq account.\n")
            append("Open this link and enter your Secret Key (from your Emergency Kit) to set a new password:\n\n")
            append(link).append("\n\nThe link expires soon. If you didn't request this, you can ignore this email.\n")
        }
        mailer.send(Mail.withText(email, "Reset your veyq password", body))
    }

    @Transactional
    fun getMaterial(req: PasswordResetTokenRequest): PasswordResetMaterial {
        val token = validToken(req.token)
        val user = users.findById(token.userId) ?: throw invalidToken()
        val recovery = keyBlobs.findByUserIdAndUnlockMethod(user.id, "RECOVERY").firstOrNull()
            ?: throw ApiException(400, "NO_RECOVERY", "This account has no recovery wrap")
        return PasswordResetMaterial(email = user.email, recoveryWrap = toWrap(recovery))
    }

    @Transactional
    fun confirm(req: PasswordResetConfirmRequest) {
        val token = validToken(req.token)
        val wrap = req.masterKeyWrap
        if (wrap.unlockMethod != MasterKeyWrap.UnlockMethod.PASSWORD) {
            throw ApiException(400, "EXPECTED_PASSWORD_WRAP", "Reset must submit a PASSWORD master-key wrap")
        }
        suites.require(wrap.suiteId)
        suites.require(req.authVerifier.suiteId)
        val userId = token.userId

        // Enforce Secret-Key possession: the client must reproduce the recovery commitment
        // (SHA-256("veyq/v1/recovery-auth" || masterKey)) stored at registration. It can only do so after
        // unwrapping the RECOVERY wrap with the Secret Key — so an email-token-only attacker cannot reset the
        // verifier and hijack the server account. Mismatch ⇒ reject (the master-key wrap they'd submit is junk).
        val recovery = keyBlobs.findByUserIdAndUnlockMethod(userId, "RECOVERY").firstOrNull()
            ?: throw ApiException(400, "NO_RECOVERY", "This account has no recovery wrap")
        val expectedTag = recovery.recoveryAuthTag
            ?: throw ApiException(400, "NO_RECOVERY_AUTH", "This account has no recovery commitment")
        val providedTag = runCatching { dec.decode(req.recoveryAuthTag) }.getOrNull()
            ?: throw ApiException(400, "BAD_RECOVERY_AUTH", "Malformed recoveryAuthTag")
        if (!MessageDigest.isEqual(expectedTag, providedTag)) {
            throw ApiException(403, "RECOVERY_AUTH_MISMATCH", "Secret Key proof failed")
        }

        // swap the PASSWORD wrap (re-keyed under the new password) and the auth verifier
        val passwordBlob = keyBlobs.findByUserIdAndUnlockMethod(userId, "PASSWORD").firstOrNull()
            ?: KeyBlob().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; unlockMethod = "PASSWORD"; createdAt = Instant.now()
                keyBlobs.persist(this)
            }
        passwordBlob.suiteId = wrap.suiteId
        passwordBlob.wrappedKey = dec.decode(wrap.wrappedKey)
        passwordBlob.kdfParams = wrap.kdfParams?.let { dec.decode(it) }

        authVerifiers.findByUserId(userId)?.apply {
            kind = req.authVerifier.kind.value
            suiteId = req.authVerifier.suiteId
            verifier = dec.decode(req.authVerifier.verifier)
        } ?: throw ApiException(400, "NO_VERIFIER", "Account has no auth verifier")

        token.consumedAt = Instant.now()
        sessions.invalidateAll(userId) // every old session dies; the user re-logs in with the new password
    }

    private fun validToken(raw: String): PasswordResetToken {
        val t = tokens.findByTokenSha256(sha256(raw)) ?: throw invalidToken()
        if (t.consumedAt != null || t.expiresAt.isBefore(Instant.now())) throw invalidToken()
        return t
    }

    private fun toWrap(blob: KeyBlob): MasterKeyWrap =
        MasterKeyWrap(
            unlockMethod = MasterKeyWrap.UnlockMethod.valueOf(blob.unlockMethod),
            suiteId = blob.suiteId,
            wrappedKey = enc.encodeToString(blob.wrappedKey),
            kdfParams = blob.kdfParams?.let { enc.encodeToString(it) },
        )


    private fun invalidToken() = ApiException(400, "INVALID_TOKEN", "Invalid or expired reset token")
}
