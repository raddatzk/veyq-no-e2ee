package com.veyq.api.auth

import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.domain.TotpCredential
import com.veyq.api.domain.TotpCredentialRepo
import com.veyq.api.domain.User
import com.veyq.api.generated.model.DisableTotpRequest
import com.veyq.api.generated.model.EnableTotpRequest
import com.veyq.api.generated.model.TotpEnrollmentResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

private const val TOTP_ISSUER = "veyq"
private const val TOTP_DIGITS = 6
private const val TOTP_PERIOD_SECONDS = 30L
private const val TOTP_ALLOWED_WINDOW = 1L

@ApplicationScoped
class TotpService @Inject constructor(
    private val totpCredentialRepo: TotpCredentialRepo,
    private val requestContext: RequestContext,
    private val passwordService: PasswordService,
) {
    private val random = SecureRandom()

    fun createEnrollment(): TotpEnrollmentResponse {
        val user = requestContext.requireUser()
        val secret = base32Encode(ByteArray(20).also(random::nextBytes))
        val accountName = user.email
        val label = "${url(TOTP_ISSUER)}:${url(accountName)}"
        val provisioningUri = "otpauth://totp/$label" +
            "?secret=$secret&issuer=${url(TOTP_ISSUER)}&algorithm=SHA1&digits=$TOTP_DIGITS&period=$TOTP_PERIOD_SECONDS"

        return TotpEnrollmentResponse(
            secret = secret,
            provisioningUri = provisioningUri,
            issuer = TOTP_ISSUER,
            accountName = accountName,
            digits = TOTP_DIGITS,
            periodSeconds = TOTP_PERIOD_SECONDS.toInt(),
        )
    }

    @Transactional
    fun enable(request: EnableTotpRequest): User {
        val user = managedCurrentUser()
        if (user.passwordSetAt == null) {
            throw BadRequestException("TOTP requires a password login")
        }
        if (!passwordService.matches(request.password, user.passwordHash)) {
            throw UnauthorizedException("Invalid password")
        }
        val secret = normalizeSecret(request.secret)
        val usedStep = verifyStep(secret, request.code)
        if (usedStep == null) {
            throw UnauthorizedException("Invalid TOTP code", "TOTP_INVALID")
        }

        val now = Instant.now()
        val credential = totpCredentialRepo.findByUser(user) ?: TotpCredential().also {
            it.user = user
            it.secret = secret
            it.createdAt = now
            it.enabledAt = now
            totpCredentialRepo.persist(it)
        }
        credential.secret = secret
        credential.enabledAt = now
        credential.lastUsedAt = now
        credential.lastUsedStep = usedStep

        return user
    }

    @Transactional
    fun disable(request: DisableTotpRequest): User {
        val user = managedCurrentUser()
        if (user.passwordSetAt == null || !passwordService.matches(request.password, user.passwordHash)) {
            throw UnauthorizedException("Invalid password")
        }
        val credential = totpCredentialRepo.findByUser(user)
            ?: throw BadRequestException("TOTP is not enabled")
        val usedStep = verifyStep(credential.secret, request.code)
        if (usedStep == null || isReusedStep(credential, usedStep)) {
            throw UnauthorizedException("Invalid TOTP code", "TOTP_INVALID")
        }
        totpCredentialRepo.delete(credential)
        return user
    }

    fun requirePasswordLoginSecondFactor(user: User, code: String?) {
        val credential = totpCredentialRepo.findByUser(user) ?: return
        val normalizedCode = code?.trim().orEmpty()
        if (normalizedCode.isBlank()) {
            throw UnauthorizedException("TOTP code required", "TOTP_REQUIRED")
        }
        val usedStep = verifyStep(credential.secret, normalizedCode)
        if (usedStep == null || isReusedStep(credential, usedStep)) {
            throw UnauthorizedException("Invalid TOTP code", "TOTP_INVALID")
        }
        credential.lastUsedAt = Instant.now()
        credential.lastUsedStep = usedStep
    }

    fun isEnabled(user: User): Boolean =
        totpCredentialRepo.findByUserId(user.id) != null

    private fun verifyStep(secret: String, code: String): Long? {
        val normalizedCode = code.trim()
        if (!Regex("^[0-9]{$TOTP_DIGITS}$").matches(normalizedCode)) return null
        val nowStep = Instant.now().epochSecond / TOTP_PERIOD_SECONDS
        return (-TOTP_ALLOWED_WINDOW..TOTP_ALLOWED_WINDOW)
            .map { offset -> nowStep + offset }
            .firstOrNull { step ->
                MessageDigest.isEqual(
                    normalizedCode.toByteArray(StandardCharsets.US_ASCII),
                    hotp(base32Decode(secret), step).toByteArray(StandardCharsets.US_ASCII),
                )
            }
    }

    private fun isReusedStep(credential: TotpCredential, usedStep: Long): Boolean =
        credential.lastUsedStep?.let { usedStep <= it } == true

    private fun hotp(secret: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 10.0.pow(TOTP_DIGITS).toInt()
        return otp.toString().padStart(TOTP_DIGITS, '0')
    }

    private fun normalizeSecret(secret: String): String {
        val normalized = secret.trim().replace(" ", "").uppercase()
        if (normalized.isBlank()) throw BadRequestException("TOTP secret is required")
        base32Decode(normalized)
        return normalized
    }

    private fun base32Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                result.append(alphabet[(buffer shr (bitsLeft - 5)) and 31])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            result.append(alphabet[(buffer shl (5 - bitsLeft)) and 31])
        }
        return result.toString()
    }

    private fun base32Decode(secret: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()
        secret.trimEnd('=').forEach { char ->
            val value = alphabet.indexOf(char)
            if (value < 0) throw BadRequestException("Invalid TOTP secret")
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        if (output.size < 10) throw BadRequestException("Invalid TOTP secret")
        return output.toByteArray()
    }

    private fun managedCurrentUser(): User =
        requestContext.requireUser()
}
