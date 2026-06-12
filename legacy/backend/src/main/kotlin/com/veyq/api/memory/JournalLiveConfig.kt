package com.veyq.api.memory

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.util.Optional

@ConfigMapping(prefix = "veyq.journal.live")
interface JournalLiveConfig {
    @get:WithName("websocket-url")
    val websocketUrl: String

    @get:WithName("token-secret")
    val tokenSecret: String

    @get:WithName("token-ttl-minutes")
    val tokenTtlMinutes: Long

    @get:WithName("sidecar-secret")
    val sidecarSecret: Optional<String>
}
