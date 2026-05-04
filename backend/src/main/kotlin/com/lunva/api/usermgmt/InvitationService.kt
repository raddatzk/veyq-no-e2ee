package com.lunva.api.usermgmt

import com.lunva.api.auth.BadRequestException
import com.lunva.api.auth.NotFoundException
import com.lunva.api.common.HashingService
import com.lunva.api.common.mail.MailService
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.User
import com.lunva.api.domain.UserInvitation
import com.lunva.api.domain.UserInvitationRepo
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
import java.util.*

@ApplicationScoped
class InvitationService @Inject constructor(
    private val invitationRepo: UserInvitationRepo,
    private val userConfig: UserConfig,
    private val mailService: MailService,
    private val transactionSynchronizationRegistry: TransactionSynchronizationRegistry,
) {
    private val random = SecureRandom()

    @Transactional
    fun createAndSendInvitation(user: User, tenant: Tenant): String {
        val now = Instant.now()
        invitationRepo.listOpenByUser(user).forEach { it.revokedAt = now }

        val token = newInvitationToken()
        UserInvitation().also {
            it.user = user
            it.tokenHash = HashingService.sha256(token)
            it.createdAt = now
            it.expiresAt = now.plus(Duration.ofDays(userConfig.invitationTtlDays))
            invitationRepo.persist(it)
        }

        val inviteUrl = "https://${tenant.domain}/invite/${user.id}#$token"

        transactionSynchronizationRegistry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) {
                    mailService.sendInvitationMail(user, inviteUrl)
                }
            }
        })

        return token
    }

    fun findByToken(token: String): UserInvitation? =
        invitationRepo.findByTokenHash(HashingService.sha256(token))

    fun checkToken(token: String, now: Instant): User {
        val invitation = findByToken(token)
            ?: throw NotFoundException("Invitation not found")

        if (invitation.revokedAt != null || invitation.acceptedAt != null || !invitation.expiresAt.isAfter(now)) {
            throw BadRequestException("Invitation is no longer valid")
        }

        return invitation.user
    }

    fun revokeTokenForUser(user: User, timestamp: Instant) {
        invitationRepo.listOpenByUser(user)
            .filter { it.acceptedAt == null }
            .forEach { it.acceptedAt = timestamp }
    }

    private fun newInvitationToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
