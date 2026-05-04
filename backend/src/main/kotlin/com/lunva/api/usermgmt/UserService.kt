package com.lunva.api.usermgmt

import com.lunva.api.auth.*
import com.lunva.api.auth.filterchain.RequestContext
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.Tenant
import com.lunva.api.domain.User
import com.lunva.api.domain.UserRepo
import com.lunva.api.generated.users.model.ChangePasswordRequest
import com.lunva.api.generated.users.model.AcceptInvitationRequest
import com.lunva.api.generated.users.model.CreateManagedUserRequest
import com.lunva.api.generated.users.model.ManagedUserSummary
import com.lunva.api.generated.users.model.ManagedUserTenantSummary
import com.lunva.api.generated.users.model.ManagedUsersResponse
import com.lunva.api.generated.users.model.ServerRole as ApiServerRole
import com.lunva.api.generated.users.model.UpdateManagedUserRequest
import com.lunva.api.generated.users.model.UpdateUserSettingsRequest
import com.lunva.api.generated.users.model.UserStatus
import com.lunva.api.generated.users.model.UserSummary
import com.lunva.api.tenant.TenantService
import com.lunva.api.tenant.toManagedTenantSummary
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.*

@ApplicationScoped
class UserService @Inject constructor(
    private val userRepo: UserRepo,
    private val requestContext: RequestContext,
    private val passwordService: PasswordService,
    private val tenantService: TenantService,
    private val invitationService: InvitationService,
    private val authService: AuthService,
) {

    private val random = SecureRandom()

    fun me(): UserSummary {
        return requestContext.user!!.toUserSummary()
    }

    fun findUser(userId: Long): User {
        return userRepo.findById(userId)
            ?: throw NotFoundException("User $userId not found")
    }

    @Transactional
    fun updateMySettings(request: UpdateUserSettingsRequest): UserSummary {
        val user = findUser(user().id)
        val displayName = request.displayName?.trim()!!
        val email = request.email?.trim()?.lowercase()!!
        val emailOwner = userRepo.findByEmail(email)
        if (emailOwner != null && emailOwner.id != user.id) {
            throw ConflictException("Email already exists")
        }

        user.displayName = displayName
        user.email = email

        return user.toUserSummary()
    }

    @Transactional
    fun changeMyPassword(request: ChangePasswordRequest) {
        val currentPassword = request.currentPassword!!
        val newPassword = request.newPassword?.takeIf { it.length >= 8 }
            ?: throw BadRequestException("newPassword must be at least 8 characters")
        val user = findUser(user().id)
        if (!passwordService.matches(currentPassword, user.passwordHash)) {
            throw UnauthorizedException("Invalid current password")
        }
        user.passwordHash = passwordService.hash(newPassword)
        user.passwordSetAt = Instant.now()
    }

    @Transactional
    fun createUser(request: CreateManagedUserRequest): ManagedUserSummary {
        val email = normalizeEmail(request.email)
        val displayName = request.displayName?.trim()!!
        val role = request.serverRole.toDomainRole()
        val tenant = tenantService.findTenantById(request.tenantId!!)

        val user = User().also {
            it.email = email
            it.displayName = displayName
            it.role = role
            it.passwordHash = passwordService.hash(randomInitialPassword())
            it.passwordSetAt = null
            userRepo.persist(it)
        }
        userRepo.flush()

        tenantService.addUserToTenant(tenant, user)

        invitationService.createAndSendInvitation(user, tenant)

        return user.toManagedUserSummary(tenant)
    }

    fun getAllUsers(): ManagedUsersResponse {
        return ManagedUsersResponse(userRepo.listAllOrderByEmail().map { it.toManagedUserSummary() })
    }

    @Transactional
    fun updateManagedUser(userId: Long, request: UpdateManagedUserRequest): ManagedUserSummary {
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

    @Transactional
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

        val rawPassword = request.password?.takeIf { it.length >= 8 }
            ?: throw BadRequestException("password must be at least 8 characters")
        val now = Instant.now()
        val invitation = invitationService.findByToken(request.token!!)
            ?: throw NotFoundException("Invitation not found")

        if (invitation.revokedAt != null || invitation.acceptedAt != null || !invitation.expiresAt.isAfter(now)) {
            throw BadRequestException("Invitation is no longer valid")
        }

        val invitedUser = invitation.user

        if (invitedUser != targetUser) {
            throw UnauthorizedException("User is already accepted")
        }

        invitedUser.passwordHash = passwordService.hash(rawPassword)
        invitedUser.passwordSetAt = now
        invitation.acceptedAt = now

        invitationService.revokeTokenForUser(invitedUser, now)

        return authService.loginUser(targetUser)
    }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun randomInitialPassword(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun user(): User = requestContext.user!!

    companion object {
        fun toUserSummary(user: User): UserSummary {
            return UserSummary(user.id, user.email, user.displayName, user.role.toUsersApiRole())
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

fun User.toUserSummary(): UserSummary {
    return UserSummary(id, email, displayName, role.toUsersApiRole())
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
