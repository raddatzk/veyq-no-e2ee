package com.lunva.api.setup

import com.lunva.api.generated.api.SetupApi
import com.lunva.api.generated.model.AdminSetupRequest
import com.lunva.api.generated.model.SetupStatusSummary
import com.lunva.api.generated.model.UserSummary
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
