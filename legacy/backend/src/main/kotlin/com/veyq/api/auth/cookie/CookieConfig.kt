package com.veyq.api.auth.cookie

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "veyq.auth.cookie")
interface CookieConfig {
    @get:WithName("access-token-name")
    val accessTokenName: String
    @get:WithName("refresh-token-name")
    val refreshTokenName: String

    @get:WithName("secure")
    val secure: Boolean
}