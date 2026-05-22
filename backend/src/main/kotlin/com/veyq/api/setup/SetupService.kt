package com.veyq.api.setup

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.LoginContext
import com.veyq.api.auth.AuthService
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.AdminSetupRequest
import com.veyq.api.generated.model.AdminPasskeySetupOptionsRequest
import com.veyq.api.generated.model.AdminPasskeySetupRequest
import com.veyq.api.generated.model.ServerRole as ApiServerRole
import com.veyq.api.generated.model.SetupStatusSummary
import com.veyq.api.generated.model.UserSummary
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant

@ApplicationScoped
class SetupService @Inject constructor(
    private val userRepo: UserRepo,
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
    private val passwordService: PasswordService,
    private val authService: AuthService,
    private val passkeyService: PasskeyService,
    private val serverState: ServerState,
) {
    fun status(): SetupStatusSummary =
        SetupStatusSummary(
            setupRequired = completedServerOwner() == null,
        )

    @Transactional
    fun createAdmin(request: AdminSetupRequest): UserSummary {
        if (completedServerOwner() != null) {
            throw ConflictException("Admin setup has already been completed")
        }

        val email = request.email.trim().lowercase()
        val user = setupUser(email, request.displayName.trim())
        passwordService.setPassword(user, request.password, Instant.now())
        userRepo.flush()
        serverState.markSetupCompleted()

        return user.toSetupSummary()
    }

    @Transactional
    fun createAdminPasskeyOptions(
        request: AdminPasskeySetupOptionsRequest,
        routingContext: RoutingContext,
    ): String {
        if (completedServerOwner() != null) {
            throw ConflictException("Admin setup has already been completed")
        }

        val user = setupUser(normalizeEmail(request.email), request.displayName.trim())
        userRepo.flush()
        return passkeyService.registrationOptionsForUser(user, routingContext)
    }

    @Transactional
    fun createAdminWithPasskey(
        request: AdminPasskeySetupRequest,
        routingContext: RoutingContext,
    ): LoginContext {
        if (completedServerOwner() != null) {
            throw ConflictException("Admin setup has already been completed")
        }

        val email = normalizeEmail(request.email)
        val user = pendingServerOwner()
            ?: throw BadRequestException("Passkey setup was not started")
        user.email = email
        user.displayName = request.displayName.trim()
        passkeyService.createPasskeyForUser(user, "Setup Passkey", request.credential, routingContext)
        serverState.markSetupCompleted()

        return authService.loginUser(user)
    }

    private fun setupUser(email: String, displayName: String): User {
        val user = pendingServerOwner() ?: User().also {
            it.passwordHash = passwordService.randomPasswordHash()
            it.role = ServerRole.ServerOwner
            it.email = email
            it.displayName = displayName
            userRepo.persist(it)
        }
        user.email = email
        user.displayName = displayName
        return user
    }

    private fun completedServerOwner(): User? =
        userRepo.listByRole(ServerRole.ServerOwner)
            .firstOrNull { it.passwordSetAt != null || passkeyCredentialRepo.countByUser(it) > 0 }

    private fun pendingServerOwner(): User? =
        userRepo.listByRole(ServerRole.ServerOwner)
            .firstOrNull { it.passwordSetAt == null && passkeyCredentialRepo.countByUser(it) == 0L }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun User.toSetupSummary(): UserSummary =
        UserSummary(
            id,
            email,
            displayName,
            ApiServerRole.ServerOwner,
            passkeyCredentialRepo.countByUser(this).toInt(),
        )
}
