package com.veyq.api.settings

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.util.Optional

@ConfigMapping(prefix = "veyq")
interface VeyqConfig {
    @get:WithName("public-web-base-url")
    val publicWebBaseUrl: Optional<String>

    @get:WithName("source-url")
    val sourceUrl: String

    @get:WithName("version")
    val version: Optional<String>

    @get:WithName("revision")
    val revision: Optional<String>

    @get:WithName("updater")
    val updater: Updater

    @get:WithName("mobile")
    val mobile: Mobile

    interface Updater {
        @get:WithName("notify-token")
        val notifyToken: Optional<String>
    }

    interface Mobile {
        @get:WithName("ios")
        val ios: Ios
    }

    interface Ios {
        @get:WithName("app-store-id")
        val appStoreId: Optional<String>
    }
}
