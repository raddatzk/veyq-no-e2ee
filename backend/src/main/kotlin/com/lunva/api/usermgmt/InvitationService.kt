package com.lunva.api.usermgmt

import com.lunva.api.common.HashingService
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.User
import com.lunva.api.domain.UserInvitation
import com.lunva.api.domain.UserInvitationRepo
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

@ApplicationScoped
class InvitationService @Inject constructor(
    private val invitationRepo: UserInvitationRepo,
    private val invitationConfig: InvitationConfig,
    private val invitationMailer: InvitationMailer
) {

    private val random = SecureRandom()

    fun createAndSendInvitation(user: User, tenant: Tenant) {
        val now = Instant.now()
        invitationRepo.listOpenByUser(user).forEach { it.revokedAt = now }

        val token = newInvitationToken()
        UserInvitation().also {
            it.user = user
            it.tokenHash = HashingService.sha256(token)
            it.createdAt = now
            it.expiresAt = now.plus(Duration.ofDays(invitationConfig.ttlDays))
            invitationRepo.persist(it)
        }

        invitationMailer.sendInvitation(user, "https://${tenant.domain}/invite/${user.id}/$token")
    }

    fun findByToken(token: String): UserInvitation? =
        invitationRepo.findByTokenHash(HashingService.sha256(token))

    fun revokeTokenForUser(user: User, timestamp: Instant) {
        invitationRepo.listOpenByUser(user)
            .filter { it.acceptedAt != null }
            .forEach { it.revokedAt = timestamp }
    }

    private fun newInvitationToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
