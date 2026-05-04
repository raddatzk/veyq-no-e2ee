package com.lunva.api.usermgmt.user

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "lunva.user")
interface UserConfig {
    @get:WithName("invitation-ttl-days")
    val invitationTtlDays: Long
}
