package com.veyq.api.setup

import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.PasswordService
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.AdminSetupRequest
import com.veyq.api.generated.model.ServerRole as ApiServerRole
import com.veyq.api.generated.model.SetupStatusSummary
import com.veyq.api.generated.model.UserSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class SetupService @Inject constructor(
    private val userRepo: UserRepo,
    private val passwordService: PasswordService,
    private val setupFilter: SetupFilter,
    private val serverState: ServerState,
) {
    fun status(): SetupStatusSummary =
        SetupStatusSummary(
            setupRequired = userRepo.count() == 0L,
        )

    @Transactional
    fun createAdmin(request: AdminSetupRequest): UserSummary {
        if (userRepo.count() > 0L) {
            throw ConflictException("Admin setup has already been completed")
        }

        val email = request.email!!.trim().lowercase()
        val user = User().also {
            it.passwordHash = passwordService.hash(request.password!!)
            it.displayName = request.displayName!!.trim()
            it.email = email
            it.role = ServerRole.ServerOwner
            it.passwordSetAt = Instant.now()
            userRepo.persist(it)
        }
        userRepo.flush()
        serverState.markSetupCompleted()

        return UserSummary(
            user.id,
            user.email,
            user.displayName,
            ApiServerRole.ServerOwner,
        )
    }
}
