package com.veyq.api.security

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "veyq.security")
interface SecurityConfig {
    @get:WithName("trust-forwarded-for")
    val trustForwardedFor: Boolean
}
