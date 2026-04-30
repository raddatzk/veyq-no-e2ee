package com.lunva.api.library.settings

import com.lunva.api.generated.settings.api.SettingsApi
import com.lunva.api.generated.settings.model.SettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class SettingsResource @Inject constructor(
    private val settingsService: SettingsService,
) : SettingsApi {
    override fun getSettings(tenantName: String, path: String): SettingsResponse =
        settingsService.settings(path)
}
