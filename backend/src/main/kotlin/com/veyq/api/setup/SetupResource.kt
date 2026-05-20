package com.veyq.api.setup

import com.veyq.api.generated.api.SetupApi
import com.veyq.api.generated.model.AdminSetupRequest
import com.veyq.api.generated.model.SetupStatusSummary
import com.veyq.api.generated.model.UserSummary
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
