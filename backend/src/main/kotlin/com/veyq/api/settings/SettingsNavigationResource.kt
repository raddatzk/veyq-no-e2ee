package com.veyq.api.settings

import com.veyq.api.generated.api.SettingsApi
import com.veyq.api.generated.model.SettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class SettingsNavigationResource @Inject constructor(
    private val settingsNavigationService: SettingsNavigationService,
) : SettingsApi {


    override fun getSettings(path: String): SettingsResponse =
        settingsNavigationService.settings(path)
}
