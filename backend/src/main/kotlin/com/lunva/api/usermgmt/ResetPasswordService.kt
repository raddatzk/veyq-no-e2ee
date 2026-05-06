package com.lunva.api.usermgmt

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.common.HashingService
import com.lunva.api.common.mail.MailService
import com.lunva.api.domain.ResetPasswordToken
import com.lunva.api.domain.ResetPasswordTokenRepo
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.usermgmt.user.UserConfig
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
        val now = Instant.now()
        resetPasswordTokenRepo.listOpenByUser(user).forEach { it.revokedAt = now }

        val token = newForgotPasswordToken()
        ResetPasswordToken().also {
            it.user = user
            it.tokenHash = HashingService.sha256(token)
            it.createdAt = now
            it.expiresAt = now.plus(Duration.ofDays(userConfig.invitationTtlDays))
            it.persistAndFlush()
        }

        val resetUrl = "https://${tenant.domain}/password/reset/${user.id}#$token"

        transactionSynchronizationRegistry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) {
                    mailService.sendPasswordResetMail(user, resetUrl, tenant)
                }
            }
        })
    }

    @Transactional
    fun checkToken(userId: Long, token: String, now: Instant) {
        val user = userRepo.findById(userId)
            ?: throw ForbiddenException("incorrect token")

        val targetToken = resetPasswordTokenRepo.findByTokenHash(HashingService.sha256(token))
            ?: throw ForbiddenException("incorrect token")

        if (targetToken.revokedAt != null || targetToken.acceptedAt != null || !targetToken.expiresAt.isAfter(now)) {
            throw BadRequestException("Invitation is no longer valid")
        }
        if (targetToken.user != user) {
            throw ForbiddenException("incorrect token")
        }

        targetToken.apply {
            acceptedAt = Instant.now()
        }
    }

    private fun newForgotPasswordToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
