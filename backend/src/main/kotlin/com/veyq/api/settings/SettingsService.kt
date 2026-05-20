package com.veyq.api.settings

import com.veyq.api.generated.model.ServerSettingsResponse
import jakarta.inject.Singleton

@Singleton
class SettingsService {

    fun serverSettings(): ServerSettingsResponse {
        return ServerSettingsResponse("Server", true)
    }
}
