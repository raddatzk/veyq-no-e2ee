package com.lunva.api.settings

import com.lunva.api.auth.filterchain.GroupOwnerRequired
import com.lunva.api.auth.filterchain.ServerRolesRequired
import com.lunva.api.auth.filterchain.TenantOwnerRequired
import com.lunva.api.domain.ServerRole
import com.lunva.api.generated.server.api.ServerApi
import com.lunva.api.generated.server.model.ServerSettingsResponse
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
