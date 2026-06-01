package com.veyq.api.usermgmt

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.common.HashingService
import com.veyq.api.common.mail.MailService
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.User
import com.veyq.api.domain.UserInvitation
import com.veyq.api.domain.UserInvitationRepo
import com.veyq.api.usermgmt.user.UserConfig
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
    fun createAndSendInvitationWithUserCompletion(user: User, tenant: Tenant) {

        val inviteUrl = createInvitation(user, tenant)

        transactionSynchronizationRegistry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) {
                    mailService.sendInvitationMailWithUserCompletion(user, inviteUrl, tenant)
                }
            }
        })
    }

    @Transactional
    fun createAndSendInvitation(user: User, tenant: Tenant) {
        val inviteUrl = createInvitation(user, tenant)

        transactionSynchronizationRegistry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                if (status == Status.STATUS_COMMITTED) {
                    mailService.sendInvitationMail(user, inviteUrl, tenant)
                }
            }
        })
    }

    private fun createInvitation(user: User, tenant: Tenant): String {
        val now = Instant.now()
        invitationRepo.listOpenByUserAndTenant(user, tenant).forEach { it.revokedAt = now }

        val token = newInvitationToken()
        UserInvitation().also {
            it.user = user
            it.tenant = tenant
            it.tokenHash = HashingService.sha256(token)
            it.createdAt = now
            it.expiresAt = now.plus(Duration.ofDays(userConfig.invitationTtlDays))
            invitationRepo.persist(it)
        }

        return "https://${tenant.domain}/invite/${user.id}#$token"
    }

    fun findByToken(token: String): UserInvitation? =
        invitationRepo.findByTokenHash(HashingService.sha256(token))

    fun checkToken(token: String, now: Instant): UserInvitation {
        val invitation = findByToken(token)
            ?: throw NotFoundException("Invitation not found")

        if (invitation.revokedAt != null || invitation.acceptedAt != null || !invitation.expiresAt.isAfter(now)) {
            throw BadRequestException("Invitation is no longer valid")
        }

        return invitation
    }

    fun acceptTokenForUserAndTenant(user: User, tenant: Tenant, timestamp: Instant) {
        invitationRepo.listOpenByUserAndTenant(user, tenant)
            .filter { it.acceptedAt == null }
            .forEach { it.acceptedAt = timestamp }
    }

    private fun newInvitationToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
