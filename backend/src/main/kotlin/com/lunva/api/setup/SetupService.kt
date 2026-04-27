package com.lunva.api.setup

import com.lunva.api.auth.ConflictException
import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.TenantRepo
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.user.UserSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

@ApplicationScoped
class SetupService @Inject constructor(
    private val userRepo: UserRepo,
    private val tenantRepo: TenantRepo,
    private val passwordService: PasswordService,
    private val setupFilter: SetupFilter,
) {
    fun status(): SetupStatusResponse =
        SetupStatusResponse(
            adminRequired = userRepo.count() == 0L,
            tenantAvailable = tenantRepo.count() > 0L,
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
            it.role = ServerRole.SERVER_OWNER
            userRepo.persist(it)
        }
        userRepo.flush()
        setupFilter.markSetupCompleted()

        return UserSummary(
            user.id,
            user.email,
            user.displayName,
            user.role,
        )
    }
}
