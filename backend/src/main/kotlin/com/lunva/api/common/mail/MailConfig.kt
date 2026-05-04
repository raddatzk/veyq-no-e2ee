package com.lunva.api.common.mail

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "lunva.mail")
interface MailConfig {
    @get:WithName("from")
    val from: String
}