package com.veyq.api.usermgmt

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.common.HashingService
import com.veyq.api.common.mail.MailService
import com.veyq.api.domain.ResetPasswordToken
import com.veyq.api.domain.ResetPasswordTokenRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.usermgmt.user.UserConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

@ApplicationScoped
class ResetPasswordService @Inject constructor(
    private val resetPasswordTokenRepo: ResetPasswordTokenRepo,
    private val userRepo: UserRepo,
    private val userConfig: UserConfig,
    private val mailService: MailService,
    private val transactionSynchronizationRegistry: TransactionSynchronizationRegistry,
) {
    private val random = SecureRandom()

    @Transactional
    fun requestNewPassword(user: User, tenant: Tenant) {
        requestReset(user, tenant, ResetKind.Password)
    }

    @Transactional
    fun requestNewPasskey(user: User, tenant: Tenant) {
        requestReset(user, tenant, ResetKind.Passkey)
    }

    private fun requestReset(user: User, tenant: Tenant, kind: ResetKind) {
        val now = Instant.now()
        resetPasswordTokenRepo.listOpenByUser(user).forEach { it.revokedAt = now }

        val token = newForgotPasswordToken()
        val expiresAt = now.plus(Duration.ofHours(userConfig.resetPasswordTtlHours))
        ResetPasswordToken().also {
            it.user = user
            it.resetDomain = tenant.domain
            it.tokenHash = HashingService.sha256(token)
            it.createdAt = now
            it.expiresAt = expiresAt
            it.persistAndFlush()
        }

        val resetUrl = when (kind) {
            ResetKind.Password -> "https://${tenant.domain}/password/reset/${user.id}#$token"
            ResetKind.Passkey -> "https://${tenant.domain}/passkey/reset/${user.id}#$token"
        }

        transactionSynchronizationRegistry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) {
                    // The existing reset mail copy is generic enough for passkey recovery and keeps token handling identical.
                    mailService.sendPasswordResetMail(user, resetUrl, expiresAt, tenant)
                }
            }
        })
    }

    @Transactional
    fun checkToken(userId: Long, token: String, now: Instant) {
        val targetToken = requireOpenToken(userId, token, now, lockForUpdate = true)

        targetToken.apply {
            acceptedAt = Instant.now()
        }
    }

    fun requireOpenToken(
        userId: Long,
        token: String,
        now: Instant,
        lockForUpdate: Boolean = false,
    ): ResetPasswordToken {
        val user = userRepo.findById(userId)
            ?: throw ForbiddenException("incorrect token")

        val tokenHash = HashingService.sha256(token)
        val targetToken = if (lockForUpdate) {
            resetPasswordTokenRepo.findByTokenHashForUpdate(tokenHash)
        } else {
            resetPasswordTokenRepo.findByTokenHash(tokenHash)
        }
            ?: throw ForbiddenException("incorrect token")

        if (targetToken.revokedAt != null || targetToken.acceptedAt != null || !targetToken.expiresAt.isAfter(now)) {
            throw BadRequestException("Password reset token is no longer valid")
        }
        if (targetToken.user != user) {
            throw ForbiddenException("incorrect token")
        }

        return targetToken
    }

    fun acceptToken(userId: Long, token: String, now: Instant) {
        requireOpenToken(userId, token, now, lockForUpdate = true).acceptedAt = Instant.now()
    }

    private fun newForgotPasswordToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private enum class ResetKind {
        Password,
        Passkey,
    }
}
