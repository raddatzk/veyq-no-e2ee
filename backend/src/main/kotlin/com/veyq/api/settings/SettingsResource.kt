package com.veyq.api.settings

import com.veyq.api.auth.filterchain.GroupOwnerRequired
import com.veyq.api.auth.filterchain.ServerRolesRequired
import com.veyq.api.auth.filterchain.TenantOwnerRequired
import com.veyq.api.domain.ServerRole
import com.veyq.api.generated.api.ServerApi
import com.veyq.api.generated.model.ServerSettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class SettingsResource @Inject constructor(
    private val settingsService: SettingsService,
) : ServerApi {
    @ServerRolesRequired(ServerRole.ServerOwner)
    override fun getServerSettings(): ServerSettingsResponse =
        settingsService.serverSettings()
}
