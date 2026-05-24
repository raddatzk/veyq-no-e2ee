package com.veyq.api.cron

import com.veyq.api.auth.token.TokenConfig
import com.veyq.api.domain.AccessTokenRepo
import com.veyq.api.domain.MemoryShareAccessTokenRepo
import com.veyq.api.domain.MemoryShareRefreshTokenRepo
import com.veyq.api.domain.RefreshTokenRepo
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class TokenCleanup @Inject constructor(
    private val accessTokenRepo: AccessTokenRepo,
    private val refreshTokenRepo: RefreshTokenRepo,
    private val shareAccessTokenRepo: MemoryShareAccessTokenRepo,
    private val shareRefreshTokenRepo: MemoryShareRefreshTokenRepo,
    private val tokenConfig: TokenConfig,
) {

    @Transactional
    @Scheduled(cron = "{veyq.auth.token.cleanup-cron}")
    fun cleanup() {
        accessTokenRepo.deleteExpiredToken(tokenConfig.cleanupAccessTokenAuditDays)
        refreshTokenRepo.deleteExpiredToken(tokenConfig.cleanupRefreshTokenAuditDays)
        shareAccessTokenRepo.deleteExpiredToken(tokenConfig.cleanupAccessTokenAuditDays)
        shareRefreshTokenRepo.deleteExpiredToken(tokenConfig.cleanupRefreshTokenAuditDays)
    }
}
