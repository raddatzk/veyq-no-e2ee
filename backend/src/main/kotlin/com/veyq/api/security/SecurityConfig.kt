package com.veyq.api.security

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName

@ConfigMapping(prefix = "veyq.security")
interface SecurityConfig {
    @get:WithName("trust-forwarded-for")
    val trustForwardedFor: Boolean

    @get:WithName("public-share-updates")
    val publicShareUpdates: PublicShareUpdates

    interface PublicShareUpdates {
        @get:WithName("max-concurrent-per-ip")
        @get:WithDefault("2")
        val maxConcurrentPerIp: Int

        @get:WithName("max-concurrent-total")
        @get:WithDefault("64")
        val maxConcurrentTotal: Int
    }
}
