package com.lunva.api.auth.token

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "lunva.auth.token")
interface TokenConfig {

    @get:WithName("access-token-ttl-minutes")
    val accessTokenTtlMinutes: Long

    @get:WithName("refresh-token-ttl-minutes")
    val refreshTokenTtlMinutes: Long

    @get:WithName("cleanup-access-token-audit-days")
    val cleanupAccessTokenAuditDays: Long

    @get:WithName("cleanup-refresh-token-audit-days")
    val cleanupRefreshTokenAuditDays: Long

    @get:WithName("cleanup-cron")
    val cleanupCron: String

    val accessTokenTtlSeconds: Int
        get() = (accessTokenTtlMinutes * 60).toInt()

    val refreshTokenTtlSeconds: Int
        get() = (refreshTokenTtlMinutes * 60).toInt()
}