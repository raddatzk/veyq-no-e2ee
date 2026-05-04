package com.lunva.api.library.explorer

import com.lunva.api.generated.settings.api.SettingsApi
import com.lunva.api.generated.settings.model.SettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class SettingsExplorerResource @Inject constructor(
    private val settingsExplorerService: SettingsExplorerService,
) : SettingsApi {


    override fun getSettings(path: String): SettingsResponse =
        settingsExplorerService.settings(path)
}
