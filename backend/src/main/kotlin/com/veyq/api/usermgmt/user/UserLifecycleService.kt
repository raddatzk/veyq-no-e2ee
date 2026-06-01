package com.veyq.api.usermgmt.user

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.auth.token.TokenService
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.DeleteMyAccountRequest
import com.veyq.api.generated.model.ManagedUserSummary
import com.veyq.api.library.media.MediaConfig
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.nio.file.Files
import java.time.Instant

@ApplicationScoped
class UserLifecycleService @Inject constructor(
    private val userRepo: UserRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val requestContext: RequestContext,
    private val passwordService: PasswordService,
    private val passkeyService: PasskeyService,
    private val tokenService: TokenService,
    private val mediaConfig: MediaConfig,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun deleteMyAccount(request: DeleteMyAccountRequest, routingContext: RoutingContext) {
        val user = managedCurrentUser()
        requireDeletionAuthentication(user, request, routingContext)
        requireCanDeactivateOrDelete(user)
        requireTenantOwnerContinuity(user)

        val replacement = deletedUserReplacement()
        val now = Instant.now()

        tokenService.revokeAllForUser(user, now)
        deleteUserPrivateContent(user)
        transferVisibleReferences(user, replacement)
        deleteAccountOnlyState(user)
        anonymizeUser(user, replacement, now)
    }

    @Transactional
    fun disableUser(userId: Long): User {
        val user = userRepo.findById(userId) ?: throw com.veyq.api.auth.NotFoundException("User not found")
        if (user.deletedAt != null || user.systemUser) throw ConflictException("User cannot be disabled")
        if (requestContext.user?.id == user.id) throw ConflictException("User cannot disable itself")
        if (user.disabledAt != null) return user
        requireCanDeactivateOrDelete(user)
        requireTenantOwnerContinuity(user)

        user.disabledAt = Instant.now()
        user.disabledBy = requestContext.user
        tokenService.revokeAllForUser(user)
        return user
    }

    @Transactional
    fun disableUserSummary(userId: Long): ManagedUserSummary =
        disableUser(userId).toManagedUserSummary()

    @Transactional
    fun enableUser(userId: Long): User {
        val user = userRepo.findById(userId) ?: throw com.veyq.api.auth.NotFoundException("User not found")
        if (user.deletedAt != null || user.systemUser) throw ConflictException("User cannot be enabled")
        user.disabledAt = null
        user.disabledBy = null
        return user
    }

    @Transactional
    fun enableUserSummary(userId: Long): ManagedUserSummary =
        enableUser(userId).toManagedUserSummary()

    private fun requireDeletionAuthentication(
        user: User,
        request: DeleteMyAccountRequest,
        routingContext: RoutingContext,
    ) {
        val password = request.password?.takeIf { it.isNotBlank() }
        val passkey = request.passkey
        if (password == null && passkey == null) {
            throw BadRequestException("Password or passkey is required")
        }
        if (password != null) {
            if (user.passwordSetAt == null || !passwordService.matches(password, user.passwordHash)) {
                throw UnauthorizedException("Invalid password")
            }
            return
        }
        passkeyService.verifyCurrentUserAssertion(passkey!!, routingContext)
    }

    private fun requireCanDeactivateOrDelete(user: User) {
        if (
            user.role == ServerRole.ServerOwner &&
            activeServerOwnersExcluding(user) <= 0
        ) {
            throw ConflictException("At least one server admin is required")
        }
    }

    private fun requireTenantOwnerContinuity(user: User) {
        tenantMemberRepo.listByUser(user)
            .filter { it.status == TenantMemberStatus.Active && it.role == TenantRole.TenantOwner }
            .forEach { membership ->
                if (tenantMemberRepo.countActiveTenantOwners(membership.tenant) <= 1) {
                    throw ConflictException("At least one tenant admin is required")
                }
            }
    }

    private fun activeServerOwnersExcluding(user: User): Long =
        entityManager.createQuery(
            """
            select count(u)
            from User u
            where u.role = :role
              and u.id <> :userId
              and u.disabledAt is null
              and u.deletedAt is null
              and u.systemUser = false
              and (u.passwordSetAt is not null or exists (
                select c.id from PasskeyCredential c where c.user = u
              ))
            """.trimIndent(),
            java.lang.Long::class.java,
        )
            .setParameter("role", ServerRole.ServerOwner)
            .setParameter("userId", user.id)
            .singleResult
            .toLong()

    private fun deletedUserReplacement(): User =
        userRepo.deletedUserReplacement()
            ?: throw IllegalStateException("Deleted-user replacement account is missing")

    private fun deleteUserPrivateContent(user: User) {
        nativeUpdate("delete from veyq.veyq_memory_participant where memory_id in (select id from veyq.veyq_memory where scope_user_id = :userId)", user)
        nativeUpdate("delete from veyq.veyq_memory_favorite where memory_id in (select id from veyq.veyq_memory where scope_user_id = :userId)", user)
        nativeUpdate("delete from veyq.veyq_memory_playlist where scope_user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_memory_track where scope_user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_media where scope_user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_memory_media_bucket where memory_id in (select id from veyq.veyq_memory where scope_user_id = :userId)", user)
        nativeUpdate("delete from veyq.veyq_memory where scope_user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_tenant_folder where scope_user_id = :userId", user)
    }

    private fun transferVisibleReferences(user: User, replacement: User) {
        nativeTransfer("update veyq.veyq_memory set owner_user_id = :replacementId where owner_user_id = :userId", user, replacement)
        nativeTransfer("update veyq.veyq_tenant_folder set owner_user_id = :replacementId where owner_user_id = :userId", user, replacement)
        nativeTransfer("update veyq.veyq_media set uploader_user_id = :replacementId where uploader_user_id = :userId", user, replacement)
        nativeTransfer("update veyq.veyq_memory_media_bucket set uploader_user_id = :replacementId where uploader_user_id = :userId", user, replacement)
        nativeTransfer("update veyq.veyq_memory_share set created_by = :replacementId where created_by = :userId", user, replacement)
        nativeTransfer("update veyq.veyq_memory_playlist set created_by_user_id = :replacementId where created_by_user_id = :userId", user, replacement)
        nativeTransfer("update veyq.veyq_memory_participant set added_by_user_id = :replacementId where added_by_user_id = :userId", user, replacement)
        nativeUpdate("delete from veyq.veyq_memory_participant where user_id = :userId", user)
    }

    private fun deleteAccountOnlyState(user: User) {
        nativeUpdate("delete from veyq.veyq_memory_favorite where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_user_capability_ack where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_tenant_group_member where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_tenant_member where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_passkey_link_token where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_passkey_credential where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_totp_credential where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_mobile_session_code where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_reset_password_token where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_user_email_change where user_id = :userId", user)
        nativeUpdate("delete from veyq.veyq_user_invitation where user_id = :userId", user)
        user.avatarStorageKey?.let { storageKey ->
            Files.deleteIfExists(storagePath(storageKey))
        }
    }

    private fun anonymizeUser(user: User, replacement: User, now: Instant) {
        user.email = "deleted-user-${user.id}@system.veyq.local"
        user.permalink = "deleted-user-${user.id}"
        user.displayName = "Deleted User"
        user.passwordHash = passwordService.randomPasswordHash()
        user.passwordSetAt = null
        user.avatarStorageKey = null
        user.avatarContentType = null
        user.avatarUpdatedAt = null
        user.role = ServerRole.ServerMember
        user.disabledAt = null
        user.disabledBy = null
        user.deletedAt = now
        user.deletedReplacementUser = replacement
    }

    private fun nativeUpdate(sql: String, user: User): Int =
        entityManager.createNativeQuery(sql)
            .setParameter("userId", user.id)
            .executeUpdate()

    private fun nativeTransfer(sql: String, user: User, replacement: User): Int =
        entityManager.createNativeQuery(sql)
            .setParameter("userId", user.id)
            .setParameter("replacementId", replacement.id)
            .executeUpdate()

    private fun storagePath(storageKey: String) =
        mediaConfig.storageDir.toAbsolutePath().normalize().resolve(storageKey).normalize()

    private fun managedCurrentUser(): User =
        userRepo.findById(requestContext.user!!.id) ?: throw UnauthorizedException("Invalid user")
}
