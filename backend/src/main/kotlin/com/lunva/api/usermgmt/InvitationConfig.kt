package com.lunva.api.usermgmt

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "lunva.auth.invitation")
interface InvitationConfig {
    @get:WithName("ttl-days")
    val ttlDays: Long

    @get:WithName("mail-from")
    val mailFrom: String
}
