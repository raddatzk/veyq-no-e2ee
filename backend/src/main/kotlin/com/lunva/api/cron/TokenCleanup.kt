package com.lunva.api.cron

import com.lunva.api.auth.token.TokenConfig
import com.lunva.api.domain.AccessTokenRepo
import com.lunva.api.domain.RefreshTokenRepo
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class TokenCleanup @Inject constructor(
    private val accessTokenRepo: AccessTokenRepo,
    private val refreshTokenRepo: RefreshTokenRepo,
    private val tokenConfig: TokenConfig,
) {

    @Transactional
    @Scheduled(cron = "{lunva.auth.token.cleanup-cron}")
    fun cleanup() {
        accessTokenRepo.deleteExpiredToken(tokenConfig.cleanupAccessTokenAuditDays)
        refreshTokenRepo.deleteExpiredToken(tokenConfig.cleanupRefreshTokenAuditDays)
    }
}
