package com.veyq.api.setup

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.LoginContext
import com.veyq.api.auth.AuthService
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.common.Permalinks
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
import com.veyq.api.settings.CapabilityAcknowledgementService
import com.veyq.api.usermgmt.user.ownAvatarUrl
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
    private val capabilityAcknowledgementService: CapabilityAcknowledgementService,
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
        val user = setupUser(email, requiredSetupDisplayName(request.displayName))
        passwordService.setPassword(user, request.password, Instant.now())
        capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(user)
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

        val user = setupUser(normalizeEmail(request.email), requiredSetupDisplayName(request.displayName))
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
        user.displayName = requiredSetupDisplayName(request.displayName)
        user.permalink = profilePermalink(user.displayName)
        passkeyService.createPasskeyForUser(user, request.passkeyDisplayName, request.credential, routingContext)
        capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(user)
        serverState.markSetupCompleted()

        return authService.loginUser(user)
    }

    private fun setupUser(email: String, displayName: String): User {
        val user = pendingServerOwner() ?: User().also {
            it.passwordHash = passwordService.randomPasswordHash()
            it.role = ServerRole.ServerOwner
            it.email = email
            it.displayName = displayName
            it.permalink = profilePermalink(displayName)
            userRepo.persist(it)
        }
        user.email = email
        user.displayName = displayName
        user.permalink = profilePermalink(displayName)
        return user
    }

    private fun completedServerOwner(): User? =
        userRepo.listByRole(ServerRole.ServerOwner)
            .firstOrNull { !it.systemUser && it.deletedAt == null && (it.passwordSetAt != null || passkeyCredentialRepo.countByUser(it) > 0) }

    private fun pendingServerOwner(): User? =
        userRepo.listByRole(ServerRole.ServerOwner)
            .firstOrNull { !it.systemUser && it.deletedAt == null && it.passwordSetAt == null && passkeyCredentialRepo.countByUser(it) == 0L }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun profilePermalink(displayName: String): String =
        Permalinks.normalize(displayName).takeIf { it.isNotBlank() && it != "me" } ?: "admin"

    private fun User.toSetupSummary(): UserSummary =
        UserSummary(
            userId = id,
            email = email,
            displayName = displayName,
            permalink = permalink,
            serverRole = ApiServerRole.ServerOwner,
            passkeyCount = passkeyCredentialRepo.countByUser(this).toInt(),
            hasPassword = passwordSetAt != null,
            totpEnabled = totpCredential != null,
            avatarUrl = ownAvatarUrl(),
        )
}

internal fun requiredSetupDisplayName(value: String): String =
    value.trim().takeIf { it.isNotBlank() }
        ?: throw BadRequestException("displayName must not be blank")
