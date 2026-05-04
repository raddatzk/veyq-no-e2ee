package com.lunva.api.setup

import com.lunva.api.generated.setup.api.SetupApi
import com.lunva.api.generated.setup.model.AdminSetupRequest
import com.lunva.api.generated.setup.model.SetupStatusSummary
import com.lunva.api.generated.setup.model.UserSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class SetupResource @Inject constructor(
    private val setupService: SetupService,
) : SetupApi {

    override fun status(): SetupStatusSummary =
        setupService.status()

    override fun createAdmin(request: AdminSetupRequest): UserSummary =
        setupService.createAdmin(request)
}
