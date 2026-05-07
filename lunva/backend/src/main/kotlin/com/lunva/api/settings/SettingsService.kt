package com.lunva.api.settings

import com.lunva.api.generated.model.ServerSettingsResponse
import jakarta.inject.Singleton

@Singleton
class SettingsService {

    fun serverSettings(): ServerSettingsResponse {
        return ServerSettingsResponse("Server", true)
    }
}
