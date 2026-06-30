package com.veyq.api.usermgmt.user

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "veyq.user")
interface UserConfig {
    @get:WithName("invitation-ttl-days")
    val invitationTtlDays: Long

    @get:WithName("email-change-ttl-hours")
    val emailChangeTtlHours: Long

    @get:WithName("reset-password-ttl-hours")
    val resetPasswordTtlHours: Long

    @get:WithName("cleanup-cron")
    val cleanupCron: String

    @get:WithName("cleanup-retention-days")
    val cleanupRetentionDays: Long
}
