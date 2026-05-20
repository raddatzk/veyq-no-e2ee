package com.veyq.api.cron

import com.veyq.api.domain.ResetPasswordTokenRepo
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
    private val userConfig: UserConfig,
) {

    @Transactional
    @Scheduled(cron = "{veyq.user.cleanup-cron}")
    fun cleanup() {
        val cutoff = Instant.now().minus(userConfig.cleanupRetentionDays, ChronoUnit.DAYS)
        resetPasswordTokenRepo.deleteInvalidBefore(cutoff)
        userInvitationRepo.deleteInvalidBefore(cutoff)
        userEmailChangeRepo.deleteInvalidBefore(cutoff)
    }
}
