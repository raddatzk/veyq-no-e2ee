package com.veyq.api.usermgmt.user

import com.veyq.api.auth.*
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.Permalinks
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.*
import com.veyq.api.tenant.toManagedTenantSummary
import com.veyq.api.security.RateLimitService
import com.veyq.api.usermgmt.EmailChangeService
import com.veyq.api.usermgmt.InvitationService
import com.veyq.api.usermgmt.ResetPasswordService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import io.vertx.ext.web.RoutingContext
import java.time.Instant
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
    private val passkeyService: PasskeyService,
) {
    fun me(): UserSummary {
        return summarize(requestContext.user!!, emailChangeService.pendingEmail(requestContext.user!!))
    }

    fun findUser(userId: Long): User {
        return userRepo.findById(userId)
            ?: throw NotFoundException("User $userId not found")
    }

    fun getUserProfile(userPermalink: String): UserProfileSummary {
        val permalink = normalizePermalink(userPermalink)
        val profileUser = userRepo.findByPermalink(permalink)
            ?: throw NotFoundException("Profile not found")
        tenantMemberRepo.findByTenantAndUser(requestContext.tenant!!, profileUser)
            ?: throw NotFoundException("Profile not found")
        return UserProfileSummary(
            displayName = profileUser.displayName,
            permalink = profileUser.permalink,
            initials = initials(profileUser.displayName),
            avatarUrl = null,
        )
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

        passwordService.updatePassword(user, currentPassword, newPassword)
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

        return user.toManagedUserSummary()
    }

    fun getAllUsers(): ManagedUsersSummary {
        return ManagedUsersSummary(userRepo.listAllOrderByEmail().map { it.toManagedUserSummary() })
    }

    fun forgotPassword(request: ForgotPasswordRequest) {
        val email = request.email.trim().lowercase()
        rateLimitService.checkForgotPassword(email)
        val user = userRepo.findByEmail(email)?.takeIf { it.passwordSetAt != null } ?: return
        val tenant = user.tenantMemberships.firstOrNull()?.tenant ?: return
        resetPasswordService.requestNewPassword(user, tenant)
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

    fun sendInvitation(userId: Long): ManagedUserSummary {
        val user = findUser(userId)
        if (user.isActive()) {
            throw ConflictException("User is already active")
        }
        val tenant = user.tenantMemberships.firstOrNull()?.tenant ?: throw BadRequestException("User has no tenant")
        invitationService.createAndSendInvitation(user, tenant)
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

        val invitation = try {
            invitationService.checkToken(request.token, now)
        } catch (exception: NotFoundException) {
            rateLimitService.recordTokenFailure("invitation", target)
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("invitation", target)
            throw exception
        }
        val invitedUser = invitation.user

        if (invitedUser != targetUser) {
            rateLimitService.recordTokenFailure("invitation", target)
            throw UnauthorizedException("User is already accepted")
        }

        if (invitedUser.passwordSetAt == null) {
            val displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("displayName must not be blank")
            val rawPassword = request.password
                ?: throw BadRequestException("password must not be blank")
            invitedUser.displayName = displayName
            if (invitedUser.permalink.startsWith("user-")) {
                invitedUser.permalink = uniquePermalink(displayName, invitedUser)
            }
            passwordService.setPassword(invitedUser, rawPassword, now)
        } else if (requestContext.user?.id != invitedUser.id) {
            throw UnauthorizedException("User must be logged in to accept this invitation")
        }

        val membership = tenantMemberRepo.findByTenantAndUser(invitation.tenant, invitedUser)
            ?: throw BadRequestException("Invitation membership is missing")
        membership.acceptedAt = now

        invitationService.acceptTokenForUserAndTenant(invitedUser, invitation.tenant, now)
        rateLimitService.recordTokenSuccess("invitation", target)

        return authService.loginUser(targetUser)
    }

    @Transactional
    fun createInvitationPasskeyOptions(
        userId: Long,
        request: PasskeyInvitationOptionsRequest,
        routingContext: RoutingContext,
    ): String {
        val targetUser = checkPendingInvitation(userId, request.token)
        return passkeyService.registrationOptionsForUser(targetUser, routingContext)
    }

    @Transactional
    fun acceptInvitationWithPasskey(
        userId: Long,
        request: AcceptPasskeyInvitationRequest,
        routingContext: RoutingContext,
    ): LoginContext {
        val invitedUser = checkPendingInvitation(userId, request.token)
        val displayName = request.displayName.trim().takeIf { it.isNotBlank() }
            ?: throw BadRequestException("displayName must not be blank")
        val now = Instant.now()

        invitedUser.displayName = displayName
        if (invitedUser.permalink.startsWith("user-")) {
            invitedUser.permalink = uniquePermalink(displayName, invitedUser)
        }
        passkeyService.createPasskeyForUser(invitedUser, "Passkey", request.credential, routingContext)

        val tenant = invitedUser.tenantMemberships.firstOrNull()?.tenant
            ?: throw BadRequestException("User has no tenant")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant, invitedUser)
            ?: throw BadRequestException("Invitation membership is missing")
        membership.acceptedAt = now

        invitationService.acceptTokenForUserAndTenant(invitedUser, tenant, now)

        return authService.loginUser(invitedUser)
    }

    private fun checkPendingInvitation(userId: Long, token: String): User {
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

        return targetUser
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

    private fun activeServerOwnerCount(): Int =
        userRepo.listByRole(ServerRole.ServerOwner).count { it.isActive() }

    private fun User.isActive(): Boolean =
        passwordSetAt != null || passkeyCredentialRepo.countByUser(this) > 0

    private fun summarize(user: User, pendingEmail: String? = null): UserSummary =
        UserSummary(
            user.id,
            user.email,
            user.displayName,
            user.permalink,
            user.role.toUsersApiRole(),
            passkeyCredentialRepo.countByUser(user).toInt(),
            pendingEmail,
        )

    companion object {
        fun toUserSummary(user: User, pendingEmail: String? = null): UserSummary {
            return UserSummary(user.id, user.email, user.displayName, user.permalink, user.role.toUsersApiRole(), 0, pendingEmail)
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
        if (passwordSetAt == null && passkeyCredentials.isEmpty()) UserStatus.Pending else UserStatus.Active,
        tenants,
    )
}

fun User.toUserSummary(pendingEmail: String? = null): UserSummary {
    return UserSummary(id, email, displayName, permalink, role.toUsersApiRole(), passkeyCredentials.size, pendingEmail)
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
