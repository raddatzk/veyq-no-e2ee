package com.lunva.api.usermgmt.user

import com.lunva.api.auth.*
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.common.mail.MailService
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.generated.model.*
import com.lunva.api.tenant.TenantService
import com.lunva.api.tenant.toManagedTenantSummary
import com.lunva.api.usermgmt.EmailChangeService
import com.lunva.api.usermgmt.InvitationService
import com.lunva.api.usermgmt.ResetPasswordService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.Instant
import com.lunva.api.generated.model.ServerRole as ApiServerRole

@ApplicationScoped
class UserService @Inject constructor(
    private val userRepo: UserRepo,
    private val requestContext: RequestContext,
    private val passwordService: PasswordService,
    private val tenantService: TenantService,
    private val invitationService: InvitationService,
    private val authService: AuthService,
    private val resetPasswordService: ResetPasswordService,
    private val mailService: MailService,
    private val emailChangeService: EmailChangeService,
) {
    fun me(): UserSummary {
        return requestContext.user!!.toUserSummary(emailChangeService.pendingEmail(requestContext.user!!))
    }

    fun findUser(userId: Long): User {
        return userRepo.findById(userId)
            ?: throw NotFoundException("User $userId not found")
    }

    @Transactional
    fun updateMySettings(request: UpdateUserSettingsRequest): UserSummary {
        val user = user()
        val displayName = request.displayName.trim()
        val email = request.email.trim().lowercase()
        val emailOwner = userRepo.findByEmail(email)
        if (emailOwner != null && emailOwner.id != user.id) {
            throw ConflictException("Email already exists")
        }

        user.displayName = displayName
        val pendingEmail = if (email != user.email) {
            emailChangeService.requestEmailChange(user, email)
        } else {
            emailChangeService.pendingEmail(user)
        }

        return user.toUserSummary(pendingEmail)
    }

    @Transactional
    fun confirmMyEmailChange(request: ConfirmEmailChangeRequest): UserSummary {
        val user = user()
        val confirmedUser = emailChangeService.confirmEmailChange(user, request.token)
        return confirmedUser.toUserSummary()
    }

    @Transactional
    fun changeMyPassword(request: ChangePasswordRequest) {
        val currentPassword = request.currentPassword
        val newPassword = request.newPassword
        val user = user()

        passwordService.updatePassword(user, currentPassword, newPassword)
    }

    @Transactional
    fun createUser(request: CreateManagedUserRequest): ManagedUserSummary {
        val email = request.email
        val displayName = request.displayName.trim()
        val role = request.serverRole.toDomainRole()
        val tenant = tenantService.findTenantById(request.tenantId)

        val user = User().also {
            it.email = email
            it.displayName = displayName
            it.role = role
            it.passwordHash = passwordService.randomPasswordHash()
            it.passwordSetAt = null
            it.persistAndFlush()
        }

        tenantService.addUserToTenant(tenant, user)

        invitationService.createAndSendInvitation(user, tenant)

        return user.toManagedUserSummary(tenant)
    }

    fun getAllUsers(): ManagedUsersSummary {
        return ManagedUsersSummary(userRepo.listAllOrderByEmail().map { it.toManagedUserSummary() })
    }

    fun forgotPassword(request: ForgotPasswordRequest) {
        val email = request.email.trim().lowercase()
        val user = userRepo.findByEmail(email)?.takeIf { it.passwordSetAt != null } ?: return
        val tenant = user.tenantMemberships.firstOrNull()?.tenant ?: return
        resetPasswordService.requestNewPassword(user, tenant)
    }

    @Transactional
    fun resetMyPassword(userId: Long, request: ResetPasswordRequest) {
        val user = findUser(userId)
        val now = Instant.now()
        resetPasswordService.checkToken(userId, request.token, now)

        passwordService.setPassword(user, request.password, now)
    }

    @Transactional
    fun updateManagedUser(userId: Long, request: UpdateManagedUserRoleRequest): ManagedUserSummary {
        val user = userRepo.findById(userId) ?: throw NotFoundException("User not found")
        val nextRole = request.serverRole.toDomainRole()

        if (
            user.role == ServerRole.ServerOwner &&
            nextRole != ServerRole.ServerOwner &&
            userRepo.countActiveByRole(ServerRole.ServerOwner) <= 1
        ) {
            throw ConflictException("At least one server admin is required")
        }

        user.role = nextRole
        return user.toManagedUserSummary()
    }

    fun sendInvitation(userId: Long): ManagedUserSummary {
        val user = findUser(userId)
        if (user.passwordSetAt != null) {
            throw ConflictException("User is already active")
        }
        val tenant = user.tenantMemberships.firstOrNull()?.tenant ?: throw BadRequestException("User has no tenant")
        invitationService.createAndSendInvitation(user, tenant)
        return user.toManagedUserSummary()
    }

    @Transactional
    fun acceptInvitation(userId: Long, request: AcceptInvitationRequest): LoginContext {
        val targetUser = findUser(userId)

        val rawPassword = request.password.takeIf { it.length >= 8 }
            ?: throw BadRequestException("password must be at least 8 characters")

        val now = Instant.now()

        val invitedUser = invitationService.checkToken(request.token, now)

        if (invitedUser != targetUser) {
            throw UnauthorizedException("User is already accepted")
        }

        passwordService.setPassword(invitedUser, rawPassword, now)

        invitationService.revokeTokenForUser(invitedUser, now)

        return authService.loginUser(targetUser)
    }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun user(): User = requestContext.user!!

    companion object {
        fun toUserSummary(user: User, pendingEmail: String? = null): UserSummary {
            return UserSummary(user.id, user.email, user.displayName, user.role.toUsersApiRole(), pendingEmail)
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
        if (passwordSetAt == null) UserStatus.Pending else UserStatus.Active,
        tenants,
    )
}

fun User.toUserSummary(pendingEmail: String? = null): UserSummary {
    return UserSummary(id, email, displayName, role.toUsersApiRole(), pendingEmail)
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
