package com.veyq.api.cron

import com.veyq.api.domain.ResetPasswordTokenRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.UserEmailChangeRepo
import com.veyq.api.domain.UserInvitationRepo
import com.veyq.api.usermgmt.user.UserConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@ApplicationScoped
class UserTokenCleanup @Inject constructor(
    private val resetPasswordTokenRepo: ResetPasswordTokenRepo,
    private val userInvitationRepo: UserInvitationRepo,
    private val userEmailChangeRepo: UserEmailChangeRepo,
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val userConfig: UserConfig,
) {

    @Transactional
    @Scheduled(cron = "{veyq.user.cleanup-cron}")
    fun cleanup() {
        val now = Instant.now()
        tenantRepo.listInactive()
            .filter { it.hasNoAcceptedOwner() && it.hasNoOpenOwnerInvitation(now) }
            .forEach(::deleteInactiveTenant)
        val cutoff = Instant.now().minus(userConfig.cleanupRetentionDays, ChronoUnit.DAYS)
        resetPasswordTokenRepo.deleteInvalidBefore(cutoff)
        userInvitationRepo.deleteInvalidBefore(cutoff)
        userEmailChangeRepo.deleteInvalidBefore(cutoff)
    }

    private fun deleteInactiveTenant(tenant: Tenant) {
        userInvitationRepo.deleteByTenant(tenant)
        tenantMemberRepo.deleteByTenant(tenant)
        tenantRepo.deleteByIdValue(tenant.id)
    }

    private fun Tenant.hasNoAcceptedOwner(): Boolean =
        tenantMemberRepo.countActiveTenantOwners(this) == 0L

    private fun Tenant.hasNoOpenOwnerInvitation(now: Instant): Boolean =
        userInvitationRepo.listOpenByTenant(this).none { invitation ->
            invitation.expiresAt.isAfter(now) &&
                tenantMemberRepo.findByTenantAndUser(this, invitation.user)?.role == TenantRole.TenantOwner
        }
}
