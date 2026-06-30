package com.veyq.api.config

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.ConfigProvider

@ApplicationScoped
class ProductionConfigGuard {
    fun validate(@Observes event: StartupEvent) {
        val config = ConfigProvider.getConfig()
        val profiles = config.getOptionalValue("quarkus.profile", String::class.java).orElse("prod")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (!profiles.contains("prod")) return

        val databasePassword = config.getOptionalValue("quarkus.datasource.password", String::class.java).orElse("")
        if (databasePassword == "change-me") {
            throw IllegalStateException("DB_PASSWORD must be set to a non-default value in production")
        }
    }
}
