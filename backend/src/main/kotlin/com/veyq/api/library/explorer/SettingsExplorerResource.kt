package com.veyq.api.library.explorer

import com.veyq.api.generated.api.SettingsApi
import com.veyq.api.generated.model.SettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class SettingsExplorerResource @Inject constructor(
    private val settingsExplorerService: SettingsExplorerService,
) : SettingsApi {


    override fun getSettings(path: String): SettingsResponse =
        settingsExplorerService.settings(path)
}
