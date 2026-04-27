package com.lunva.api.server

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName

@ConfigMapping(prefix = "lunva.server")
interface ServerConfig {

    @get:WithName("name")
    val name: String
}