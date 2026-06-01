package com.veyq.api.usermgmt.user

import com.veyq.api.auth.*
import com.veyq.api.auth.passkey.PasskeyHostService
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.Permalinks
import com.veyq.api.common.apiPathSegment as url
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.TotpCredentialRepo
import com.veyq.api.domain.User
import com.veyq.api.domain.UserInvitation
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.*
import com.veyq.api.library.media.MediaConfig
import com.veyq.api.memory.MemoryService
import com.veyq.api.tenant.toManagedTenantSummary
import com.veyq.api.security.RateLimitService
import com.veyq.api.settings.CapabilityAcknowledgementService
import com.veyq.api.usermgmt.EmailChangeService
import com.veyq.api.usermgmt.InvitationService
import com.veyq.api.usermgmt.ResetPasswordService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.Response
import io.vertx.ext.web.RoutingContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import com.veyq.api.generated.model.ServerRole as ApiServerRole

@ApplicationScoped
class UserService @Inject constructor(
    private val userRepo: UserRepo,
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
    private val requestContext: RequestContext,
    private val passwordService: PasswordService,
    private val tenantMemberRepo: TenantMemberRepo,
    private val invitationService: InvitationService,
    private val authService: AuthService,
    private val resetPasswordService: ResetPasswordService,
    private val emailChangeService: EmailChangeService,
    private val rateLimitService: RateLimitService,
    private val passkeyHostService: PasskeyHostService,
    private val passkeyService: PasskeyService,
    private val memoryService: MemoryService,
    private val mediaConfig: MediaConfig,
    private val capabilityAcknowledgementService: CapabilityAcknowledgementService,
    private val totpCredentialRepo: TotpCredentialRepo,
) {
    fun me(): UserSummary {
        return summarize(requestContext.user!!, emailChangeService.pendingEmail(requestContext.user!!))
    }

    fun findUser(userId: Long): User {
        return userRepo.findById(userId)
            ?: throw NotFoundException("User $userId not found")
    }

    fun getUserProfile(userPermalink: String): UserProfileSummary {
        val profileUser = visibleProfileUser(userPermalink)
        return UserProfileSummary(
            displayName = profileUser.displayName,
            permalink = profileUser.permalink,
            initials = initials(profileUser.displayName),
            memories = memoryService.listParticipantRootMemories(profileUser),
            avatarUrl = profileUser.avatarUrlForTenant(requestContext.tenant!!.name),
        )
    }

    fun myAvatar(): Response =
        avatarResponse(managedUser())

    fun getUserProfileAvatar(userPermalink: String): Response =
        avatarResponse(visibleProfileUser(userPermalink))

    @Transactional
    fun updateMyAvatar(uploadPath: Path): UserSummary {
        val user = managedUser()
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("Avatar file is required", "AVATAR_REQUIRED")
        if (size > MAX_AVATAR_BYTES) throw PayloadTooLargeException("Avatar file is too large")
        val contentType = detectAvatarContentType(uploadPath)
        val oldStorageKey = user.avatarStorageKey
        val storageKey = avatarStorageKey(user, contentType)
        writeAvatarFile(uploadPath, storageKey)
        user.avatarStorageKey = storageKey
        user.avatarContentType = contentType
        user.avatarUpdatedAt = Instant.now()
        oldStorageKey?.let(::deleteAvatarFile)
        return summarize(user, emailChangeService.pendingEmail(user))
    }

    @Transactional
    fun deleteMyAvatar(): UserSummary {
        val user = managedUser()
        val oldStorageKey = user.avatarStorageKey
        user.avatarStorageKey = null
        user.avatarContentType = null
        user.avatarUpdatedAt = null
        oldStorageKey?.let(::deleteAvatarFile)
        return summarize(user, emailChangeService.pendingEmail(user))
    }

    @Transactional
    fun updateMySettings(request: UpdateUserSettingsRequest): UserSummary {
        val user = managedUser()
        val displayName = request.displayName.trim()
        val permalink = normalizePermalink(request.permalink)
        val email = request.email.trim().lowercase()
        val emailOwner = userRepo.findByEmail(email)
        if (emailOwner != null && emailOwner.id != user.id) {
            throw ConflictException("Email already exists")
        }
        val permalinkOwner = userRepo.findByPermalink(permalink)
        if (permalinkOwner != null && permalinkOwner.id != user.id) {
            throw ConflictException("Permalink already exists")
        }

        user.displayName = displayName
        user.permalink = permalink
        val pendingEmail = if (email != user.email) {
            emailChangeService.requestEmailChange(user, email)
        } else {
            emailChangeService.pendingEmail(user)
        }

        return summarize(user, pendingEmail)
    }

    @Transactional
    fun confirmMyEmailChange(request: ConfirmEmailChangeRequest): UserSummary {
        val user = managedUser()
        val target = user.id.toString()
        rateLimitService.checkTokenFailureAllowed("email-change", target)
        val confirmedUser = try {
            emailChangeService.confirmEmailChange(user, request.token)
        } catch (exception: UnauthorizedException) {
            rateLimitService.recordTokenFailure("email-change", target)
            throw exception
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("email-change", target)
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("email-change", target)
            throw exception
        }
        rateLimitService.recordTokenSuccess("email-change", target)
        return summarize(confirmedUser)
    }

    @Transactional
    fun changeMyPassword(request: ChangePasswordRequest) {
        val currentPassword = request.currentPassword
        val newPassword = request.newPassword
        val user = managedUser()

        if (user.passwordSetAt == null) {
            passwordService.setPassword(user, newPassword, Instant.now())
            return
        }

        passwordService.updatePassword(user, currentPassword ?: "", newPassword)
    }

    @Transactional
    fun deleteMyPassword(request: DeleteMyPasswordRequest): UserSummary {
        val user = managedUser()
        if (user.passwordSetAt == null || !passwordService.matches(request.password, user.passwordHash)) {
            throw UnauthorizedException("Invalid password")
        }
        if (passkeyCredentialRepo.countByUser(user) == 0L) {
            throw ConflictException("Cannot remove the last login method")
        }

        user.passwordHash = passwordService.randomPasswordHash()
        user.passwordSetAt = null
        totpCredentialRepo.findByUser(user)?.let {
            user.totpCredential = null
            totpCredentialRepo.delete(it)
        }

        return summarize(user)
    }

    @Transactional
    fun createUser(request: CreateManagedUserRequest): ManagedUserSummary {
        managedUser()
        val email = normalizeEmail(request.email)

        if (userRepo.findByEmail(email) != null) {
            throw ConflictException("Email already exists")
        }

        val user = User().also {
            it.email = email
            it.displayName = email.substringBefore("@").ifBlank { email }
            it.permalink = uniquePermalink(it.displayName)
            it.role = ServerRole.ServerMember
            it.passwordHash = passwordService.randomPasswordHash()
            it.passwordSetAt = null
            it.persistAndFlush()
        }
        capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(user)

        return user.toManagedUserSummary()
    }

    fun getAllUsers(): ManagedUsersSummary {
        return ManagedUsersSummary(userRepo.listManagedOrderByEmail().map { it.toManagedUserSummary() })
    }

    fun forgotPassword(request: ForgotPasswordRequest) {
        val email = request.email.trim().lowercase()
        rateLimitService.checkForgotPassword(email)
        val user = userRepo.findByEmail(email)?.takeIf { it.passwordSetAt != null } ?: return
        val tenant = user.tenantMemberships.firstOrNull()?.tenant ?: return
        resetPasswordService.requestNewPassword(user, tenant)
    }

    fun forgotPasskey(request: ForgotPasswordRequest, routingContext: RoutingContext) {
        val email = request.email.trim().lowercase()
        rateLimitService.checkForgotPassword(email)
        val user = userRepo.findByEmail(email)?.takeIf { passkeyCredentialRepo.countByUser(it) > 0 } ?: return
        val domain = passkeyHostService.requireAllowedCurrentDomain(routingContext)
        val tenant = user.tenantMemberships
            .firstOrNull { it.status == TenantMemberStatus.Active && it.tenant.domain == domain }
            ?.tenant
            ?: return
        resetPasswordService.requestNewPasskey(user, tenant)
    }

    @Transactional
    fun resetMyPassword(userId: Long, request: ResetPasswordRequest) {
        val user = findUser(userId)
        val now = Instant.now()
        val target = userId.toString()
        rateLimitService.checkTokenFailureAllowed("password-reset", target)
        try {
            resetPasswordService.checkToken(userId, request.token, now)
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("password-reset", target)
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("password-reset", target)
            throw exception
        }

        passwordService.setPassword(user, request.password, now)
        rateLimitService.recordTokenSuccess("password-reset", target)
    }

    @Transactional
    fun createResetPasskeyOptions(
        userId: Long,
        request: PasskeyResetOptionsRequest,
        routingContext: RoutingContext,
    ): String {
        val token = validatePasskeyResetToken(userId, request.token, routingContext)
        return passkeyService.registrationOptionsForUser(token.user, routingContext)
    }

    @Transactional
    fun resetMyPasskey(
        userId: Long,
        request: ResetPasskeyRequest,
        routingContext: RoutingContext,
    ): LoginContext {
        val now = Instant.now()
        val target = userId.toString()
        rateLimitService.checkTokenFailureAllowed("passkey-reset", target)
        val token = try {
            validatePasskeyResetToken(userId, request.token, routingContext, now, lockForUpdate = true)
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("passkey-reset", target)
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("passkey-reset", target)
            throw exception
        }

        passkeyService.resetPasskeyForUser(token.user, request.displayName, request.credential, routingContext)
        token.acceptedAt = now
        rateLimitService.recordTokenSuccess("passkey-reset", target)
        return authService.loginUser(token.user)
    }

    @Transactional
    fun updateManagedUser(userId: Long, request: UpdateManagedUserRoleRequest): ManagedUserSummary {
        val user = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val nextRole = request.serverRole.toDomainRole()

        if (
            user.role == ServerRole.ServerOwner &&
            nextRole != ServerRole.ServerOwner &&
            activeServerOwnerCount() <= 1
        ) {
            throw ConflictException("At least one server admin is required")
        }

        user.role = nextRole
        return user.toManagedUserSummary()
    }

    @Transactional
    fun acceptInvitation(userId: Long, request: AcceptInvitationRequest): LoginContext {
        val targetUser = findUser(userId)

        if (requestContext.user != null && targetUser.id != requestContext.user?.id) {
            throw ConflictException("You are already logged in with another user")
        }

        val now = Instant.now()
        val target = targetUser.id.toString()
        rateLimitService.checkTokenFailureAllowed("invitation", target)

        val invitation = invitationService.findByToken(request.token)
        if (invitation == null) {
            rateLimitService.recordTokenFailure("invitation", target)
            throw NotFoundException("Invitation not found")
        }

        val invitedUser = invitation.user
        if (invitedUser != targetUser) {
            rateLimitService.recordTokenFailure("invitation", target)
            throw UnauthorizedException("User is already accepted")
        }

        if (invitation.acceptedAt != null) {
            val membership = tenantMemberRepo.findByTenantAndUser(invitation.tenant, invitedUser)
            if (requestContext.user?.id == invitedUser.id && membership?.status == TenantMemberStatus.Active) {
                rateLimitService.recordTokenSuccess("invitation", target)
                return authService.loginUser(targetUser)
            }
            rateLimitService.recordTokenFailure("invitation", target)
            throw BadRequestException("Invitation is no longer valid")
        }

        if (invitation.revokedAt != null || !invitation.expiresAt.isAfter(now)) {
            rateLimitService.recordTokenFailure("invitation", target)
            throw BadRequestException("Invitation is no longer valid")
        }

        val invitedUserHasPasskey = passkeyCredentialRepo.countByUser(invitedUser) > 0
        if (invitedUser.passwordSetAt == null && !invitedUserHasPasskey) {
            val displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("displayName must not be blank")
            val rawPassword = request.password
                ?: throw BadRequestException("password must not be blank")
            invitedUser.displayName = displayName
            if (invitedUser.permalink.startsWith("user-")) {
                invitedUser.permalink = uniquePermalink(displayName, invitedUser)
            }
            passwordService.setPassword(invitedUser, rawPassword, now)
            capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(invitedUser)
        } else if (requestContext.user?.id != invitedUser.id) {
            throw UnauthorizedException("User must be logged in to accept this invitation")
        }

        val membership = tenantMemberRepo.findByTenantAndUser(invitation.tenant, invitedUser)
            ?: throw BadRequestException("Invitation membership is missing")
        membership.acceptedAt = now
        membership.status = TenantMemberStatus.Active
        membership.removedAt = null
        membership.removedBy = null
        activateTenantForInitialOwnerIfNeeded(invitation.tenant, membership.role, now)

        invitationService.acceptTokenForUserAndTenant(invitedUser, invitation.tenant, now)
        rateLimitService.recordTokenSuccess("invitation", target)

        return authService.loginUser(targetUser)
    }

    @Transactional
    fun invitationMetadata(userId: Long, request: InvitationMetadataRequest): InvitationMetadataResponse {
        val invitation = checkOpenInvitation(userId, request.token)
        return InvitationMetadataResponse(
            email = invitation.user.email,
            requiresLogin = userHasLogin(invitation.user),
        )
    }

    @Transactional
    fun createInvitationPasskeyOptions(
        userId: Long,
        request: PasskeyInvitationOptionsRequest,
        routingContext: RoutingContext,
    ): String {
        val invitation = checkPendingInvitation(userId, request.token)
        return passkeyService.registrationOptionsForUser(invitation.user, routingContext)
    }

    @Transactional
    fun acceptInvitationWithPasskey(
        userId: Long,
        request: AcceptPasskeyInvitationRequest,
        routingContext: RoutingContext,
    ): LoginContext {
        val invitation = checkPendingInvitation(userId, request.token)
        val invitedUser = invitation.user
        val displayName = request.displayName.trim().takeIf { it.isNotBlank() }
            ?: throw BadRequestException("displayName must not be blank")
        val now = Instant.now()

        invitedUser.displayName = displayName
        if (invitedUser.permalink.startsWith("user-")) {
            invitedUser.permalink = uniquePermalink(displayName, invitedUser)
        }
        passkeyService.createPasskeyForUser(invitedUser, request.passkeyDisplayName, request.credential, routingContext)
        capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(invitedUser)

        val tenant = invitation.tenant
        val membership = tenantMemberRepo.findByTenantAndUser(tenant, invitedUser)
            ?: throw BadRequestException("Invitation membership is missing")
        membership.acceptedAt = now
        membership.status = TenantMemberStatus.Active
        membership.removedAt = null
        membership.removedBy = null
        activateTenantForInitialOwnerIfNeeded(tenant, membership.role, now)

        invitationService.acceptTokenForUserAndTenant(invitedUser, tenant, now)

        return authService.loginUser(invitedUser)
    }

    private fun checkPendingInvitation(userId: Long, token: String): UserInvitation {
        val targetUser = findUser(userId)

        if (requestContext.user != null && targetUser.id != requestContext.user?.id) {
            throw ConflictException("You are already logged in with another user")
        }

        if (targetUser.passwordSetAt != null || passkeyCredentialRepo.countByUser(targetUser) > 0) {
            throw ConflictException("User is already active")
        }

        val invitation = invitationService.checkToken(token, Instant.now())
        if (invitation.user != targetUser) {
            throw UnauthorizedException("User is already accepted")
        }

        return invitation
    }

    private fun checkOpenInvitation(userId: Long, token: String): UserInvitation {
        val targetUser = findUser(userId)
        val invitation = invitationService.checkToken(token, Instant.now())
        if (invitation.user != targetUser) {
            throw UnauthorizedException("User is already accepted")
        }
        return invitation
    }

    private fun userHasLogin(user: User): Boolean =
        user.passwordSetAt != null || passkeyCredentialRepo.countByUser(user) > 0

    private fun activateTenantForInitialOwnerIfNeeded(tenant: Tenant, role: TenantRole, timestamp: Instant) {
        if (tenant.activatedAt == null && role == TenantRole.TenantOwner) {
            tenant.activatedAt = timestamp
        }
    }

    private fun validatePasskeyResetToken(
        userId: Long,
        token: String,
        routingContext: RoutingContext,
        now: Instant = Instant.now(),
        lockForUpdate: Boolean = false,
    ): com.veyq.api.domain.ResetPasswordToken {
        val resetToken = resetPasswordService.requireOpenToken(userId, token, now, lockForUpdate)
        val relyingPartyId = passkeyService.currentRelyingPartyId(routingContext)
        if (resetToken.resetDomain != relyingPartyId) {
            throw ForbiddenException("Passkey reset token is not valid for this domain")
        }
        val hasAcceptedMembership = tenantMemberRepo.listByUser(resetToken.user)
            .any { it.status == TenantMemberStatus.Active && it.tenant.domain == relyingPartyId }
        if (!hasAcceptedMembership) {
            throw ForbiddenException("User is not member of this tenant")
        }
        return resetToken
    }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun normalizePermalink(value: String?): String {
        val permalink = value?.let(Permalinks::normalize)?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("permalink must not be blank")
        if (!Permalinks.isValid(permalink) || permalink == "me") throw BadRequestException("Invalid permalink")
        return permalink
    }

    private fun uniquePermalink(displayName: String, currentUser: User? = null): String {
        val base = Permalinks.normalize(displayName).takeIf { it.isNotBlank() && it != "me" } ?: "user"
        var candidate = base
        var suffix = 2
        while (true) {
            val owner = userRepo.findByPermalink(candidate)
            if (owner == null || owner.id == currentUser?.id) return candidate
            val nextSuffix = "-${suffix++}"
            candidate = base.take(120 - nextSuffix.length).trimEnd('-') + nextSuffix
        }
    }

    private fun initials(displayName: String): String =
        displayName
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }

    private fun user(): User = requestContext.user!!

    private fun managedUser(): User =
        userRepo.findById(user().id) ?: throw UnauthorizedException("Invalid user")

    private fun visibleProfileUser(userPermalink: String): User {
        val permalink = normalizePermalink(userPermalink)
        val profileUser = userRepo.findByPermalink(permalink)
            ?: throw NotFoundException("Profile not found")
        tenantMemberRepo.findByTenantAndUser(requestContext.tenant!!, profileUser)
            ?: throw NotFoundException("Profile not found")
        return profileUser
    }

    private fun avatarResponse(user: User): Response {
        val storageKey = user.avatarStorageKey ?: throw NotFoundException("Avatar not found")
        val contentType = user.avatarContentType ?: throw NotFoundException("Avatar not found")
        val path = storagePath(storageKey)
        if (!Files.exists(path)) throw NotFoundException("Avatar not found")
        return Response.ok(path.toFile(), contentType)
            .header("Cache-Control", "private, max-age=3600")
            .build()
    }

    private fun detectAvatarContentType(path: Path): String {
        val image = ImageIO.read(path.toFile()) ?: throw BadRequestException("Avatar image is invalid", "AVATAR_INVALID")
        if (image.width <= 0 || image.height <= 0) throw BadRequestException("Avatar image is invalid", "AVATAR_INVALID")
        ImageIO.createImageInputStream(path.toFile()).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw BadRequestException("Avatar image is invalid", "AVATAR_INVALID")
            val reader = readers.next()
            val format = reader.formatName.lowercase()
            reader.dispose()
            return when (format) {
                "jpeg", "jpg" -> "image/jpeg"
                "png" -> "image/png"
                else -> throw BadRequestException("Avatar image must be JPEG or PNG", "AVATAR_TYPE_UNSUPPORTED")
            }
        }
    }

    private fun avatarStorageKey(user: User, contentType: String): String {
        val extension = if (contentType == "image/png") "png" else "jpg"
        return "users/${user.id}/avatar-${UUID.randomUUID()}.$extension"
    }

    private fun writeAvatarFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun deleteAvatarFile(storageKey: String) {
        Files.deleteIfExists(storagePath(storageKey))
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid avatar storage key")
        return path
    }

    private fun activeServerOwnerCount(): Int =
        userRepo.listByRole(ServerRole.ServerOwner).count { it.isActive() }

    private fun User.isActive(): Boolean =
        disabledAt == null &&
            deletedAt == null &&
            !systemUser &&
            (passwordSetAt != null || passkeyCredentialRepo.countByUser(this) > 0)

    private fun summarize(user: User, pendingEmail: String? = null): UserSummary =
        UserSummary(
            userId = user.id,
            email = user.email,
            displayName = user.displayName,
            permalink = user.permalink,
            serverRole = user.role.toUsersApiRole(),
            passkeyCount = passkeyCredentialRepo.countByUser(user).toInt(),
            hasPassword = user.passwordSetAt != null,
            totpEnabled = user.totpCredential != null,
            avatarUrl = user.ownAvatarUrl(),
            pendingEmail = pendingEmail,
        )

    companion object {
        private const val MAX_AVATAR_BYTES = 5L * 1024L * 1024L

        fun toUserSummary(user: User, pendingEmail: String? = null): UserSummary {
            return UserSummary(
                userId = user.id,
                email = user.email,
                displayName = user.displayName,
                permalink = user.permalink,
                serverRole = user.role.toUsersApiRole(),
                passkeyCount = 0,
                hasPassword = user.passwordSetAt != null,
                totpEnabled = user.totpCredential != null,
                avatarUrl = user.ownAvatarUrl(),
                pendingEmail = pendingEmail,
            )
        }
    }
}

fun User.toManagedUserSummary(knownTenant: Tenant? = null): ManagedUserSummary {
    val tenants = if (knownTenant != null) {
        listOf(knownTenant.toManagedTenantSummary())
    } else {
        tenantMemberships
            .map { it.tenant }
            .sortedBy { it.name }
            .map { it.toManagedTenantSummary() }
    }
    return ManagedUserSummary(
        id,
        email,
        displayName,
        role.toUsersApiRole(),
        managementStatus(),
        tenants,
    )
}

private fun User.managementStatus(): UserStatus =
    when {
        deletedAt != null -> UserStatus.Deleted
        disabledAt != null -> UserStatus.Disabled
        passwordSetAt == null && passkeyCredentials.isEmpty() -> UserStatus.Pending
        else -> UserStatus.Active
    }

fun User.toUserSummary(pendingEmail: String? = null): UserSummary {
    return UserSummary(
        userId = id,
        email = email,
        displayName = displayName,
        permalink = permalink,
        serverRole = role.toUsersApiRole(),
        passkeyCount = passkeyCredentials.size,
        hasPassword = passwordSetAt != null,
        totpEnabled = totpCredential != null,
        avatarUrl = ownAvatarUrl(),
        pendingEmail = pendingEmail,
    )
}

fun User.ownAvatarUrl(): String? =
    avatarUpdatedAt?.takeIf { avatarStorageKey != null }?.let { "/api/users/me/avatar?v=${url(it.toString())}" }

fun User.avatarUrlForTenant(tenantName: String): String? =
    avatarUpdatedAt?.takeIf { avatarStorageKey != null }?.let {
        "/api/${url(tenantName)}/profiles/${url(permalink)}/avatar?v=${url(it.toString())}"
    }

fun ServerRole.toUsersApiRole(): ApiServerRole =
    when (this) {
        ServerRole.ServerOwner -> ApiServerRole.ServerOwner
        ServerRole.ServerMember -> ApiServerRole.ServerMember
    }

fun ApiServerRole.toDomainRole(): ServerRole =
    when (this) {
        ApiServerRole.ServerOwner -> ServerRole.ServerOwner
        ApiServerRole.ServerMember -> ServerRole.ServerMember
    }
