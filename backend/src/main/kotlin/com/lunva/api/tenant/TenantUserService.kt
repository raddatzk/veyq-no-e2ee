package com.lunva.api.tenant

import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.generated.model.TenantUserSettingsResponse
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class TenantUserService @Inject constructor(
    private val requestContext: RequestContext,
) {

    fun tenantUserSettings(tenantName: String): TenantUserSettingsResponse =
        TenantUserSettingsResponse(tenant().id, tenant().name, user().id, user().displayName)

    private fun tenant() = requestContext.tenant!!
    private fun user() = requestContext.user!!
}
