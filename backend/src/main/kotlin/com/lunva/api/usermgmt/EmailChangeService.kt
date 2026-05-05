package com.lunva.api.usermgmt

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.ForbiddenException
import com.lunva.api.common.HashingService
import com.lunva.api.common.mail.MailService
import com.lunva.api.domain.User
import com.lunva.api.domain.UserEmailChange
import com.lunva.api.domain.UserEmailChangeRepo
import com.lunva.api.domain.UserRepo
import com.lunva.api.usermgmt.user.UserConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.Transactional
import jakarta.transaction.TransactionSynchronizationRegistry
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@ApplicationScoped
class EmailChangeService @Inject constructor(
    private val emailChangeRepo: UserEmailChangeRepo,
    private val userRepo: UserRepo,
    private val userConfig: UserConfig,
    private val mailService: MailService,
    private val transactionSynchronizationRegistry: TransactionSynchronizationRegistry,
) {
    private val random = SecureRandom()

    @Transactional
    fun requestEmailChange(user: User, pendingEmail: String): String {
        val now = Instant.now()
        emailChangeRepo.listOpenByUser(user).forEach { it.revokedAt = now }

        val token = newEmailChangeToken()
        UserEmailChange().also {
            it.user = user
            it.pendingEmail = pendingEmail
            it.tokenHash = HashingService.sha256(token)
            it.createdAt = now
            it.expiresAt = now.plus(Duration.ofDays(userConfig.invitationTtlDays))
            emailChangeRepo.persist(it)
        }

        val tenant = user.tenantMemberships.firstOrNull()?.tenant
            ?: throw BadRequestException("User has no tenant")
        val confirmUrl = "https://${tenant.domain}/email/confirm#$token"

        transactionSynchronizationRegistry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) {
                    mailService.sendEmailChangeConfirmationMail(user, pendingEmail, confirmUrl)
                }
            }
        })

        return pendingEmail
    }

    @Transactional
    fun confirmEmailChange(user: User, token: String): User {
        val now = Instant.now()
        val emailChange = emailChangeRepo.findByTokenHash(HashingService.sha256(token))
            ?: throw ForbiddenException("incorrect token")

        if (emailChange.revokedAt != null || emailChange.acceptedAt != null || !emailChange.expiresAt.isAfter(now)) {
            throw BadRequestException("Email change is no longer valid")
        }
        if (emailChange.user.id != user.id) {
            throw ForbiddenException("incorrect token")
        }

        val emailOwner = userRepo.findByEmail(emailChange.pendingEmail)
        if (emailOwner != null && emailOwner.id != user.id) {
            throw ConflictException("Email already exists")
        }

        user.email = emailChange.pendingEmail
        emailChange.acceptedAt = now
        emailChangeRepo.listOpenByUser(user)
            .filter { it.id != emailChange.id }
            .forEach { it.revokedAt = now }
        return user
    }

    fun pendingEmail(user: User): String? =
        emailChangeRepo.listOpenByUser(user)
            .filter { it.expiresAt.isAfter(Instant.now()) }
            .maxByOrNull { it.createdAt }
            ?.pendingEmail

    private fun newEmailChangeToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
