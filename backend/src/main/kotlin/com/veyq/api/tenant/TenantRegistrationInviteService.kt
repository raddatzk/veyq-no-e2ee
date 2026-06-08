package com.veyq.api.tenant

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.LoginContext
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.PasswordService
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.auth.AuthService
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.auth.passkey.PasskeyHostService
import com.veyq.api.auth.passkey.PasskeyService
import com.veyq.api.common.HashingService
import com.veyq.api.common.Permalinks
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantRegistrationInvite
import com.veyq.api.domain.TenantRegistrationInviteRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserRepo
import com.veyq.api.generated.model.CreateTenantRegistrationInviteRequest
import com.veyq.api.generated.model.JoinTenantRegistrationInviteRequest
import com.veyq.api.generated.model.JoinTenantRegistrationInviteWithPasskeyRequest
import com.veyq.api.generated.model.TenantRegistrationInvitePasskeyOptionsRequest
import com.veyq.api.generated.model.TenantRegistrationInviteCreatedResponse
import com.veyq.api.generated.model.TenantRegistrationInviteMetadataResponse
import com.veyq.api.generated.model.TenantRegistrationInviteStatus
import com.veyq.api.generated.model.TenantRegistrationInviteSummary
import com.veyq.api.generated.model.TenantRegistrationInvitesResponse
import com.veyq.api.security.RateLimitService
import com.veyq.api.settings.CapabilityAcknowledgementService
import com.veyq.api.settings.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import io.vertx.ext.web.RoutingContext
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

@ApplicationScoped
class TenantRegistrationInviteService @Inject constructor(
    private val requestContext: RequestContext,
    private val routingContext: RoutingContext,
    private val tenantRegistrationInviteRepo: TenantRegistrationInviteRepo,
    private val tenantRepo: TenantRepo,
    private val tenantService: TenantService,
    private val tenantMemberRepo: TenantMemberRepo,
    private val userRepo: UserRepo,
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
    private val passwordService: PasswordService,
    private val authService: AuthService,
    private val passkeyHostService: PasskeyHostService,
    private val passkeyService: PasskeyService,
    private val rateLimitService: RateLimitService,
    private val capabilityAcknowledgementService: CapabilityAcknowledgementService,
    private val veyqConfig: VeyqConfig,
) {
    private val random = SecureRandom()

    @Transactional
    fun create(request: CreateTenantRegistrationInviteRequest): TenantRegistrationInviteCreatedResponse {
        val now = Instant.now()
        val expiresInDays = request.expiresInDays
        if (expiresInDays < 1 || expiresInDays > 365) {
            throw BadRequestException("expiresInDays must be between 1 and 365")
        }
        val maxRegistrations = request.maxRegistrations
        if (maxRegistrations != null && maxRegistrations < 1) {
            throw BadRequestException("maxRegistrations must be positive")
        }

        val token = newInviteToken()
        val tenant = tenant()
        val invite = TenantRegistrationInvite().also {
            it.tenant = tenant
            it.createdBy = user()
            it.tokenHash = HashingService.sha256(token)
            it.token = token
            it.expiresAt = now.plusSeconds(expiresInDays.toLong() * 24L * 60L * 60L)
            it.maxRegistrations = maxRegistrations
            it.registrationCount = 0
            it.createdAt = now
            tenantRegistrationInviteRepo.persist(it)
        }

        return TenantRegistrationInviteCreatedResponse(
            invite = invite.toSummary(now),
            joinUrl = invite.joinUrl(),
            token = token,
        )
    }

    fun list(): TenantRegistrationInvitesResponse {
        val now = Instant.now()
        return TenantRegistrationInvitesResponse(
            tenantRegistrationInviteRepo.listByTenant(tenant()).map { it.toSummary(now) }
        )
    }

    @Transactional
    fun revoke(inviteId: Long): TenantRegistrationInviteSummary {
        val invite = tenantRegistrationInviteRepo.findByTenantAndId(tenant(), inviteId)
            ?: throw NotFoundException("Tenant registration invite not found")
        if (invite.revokedAt == null) {
            invite.revokedAt = Instant.now()
        }
        return invite.toSummary(Instant.now())
    }

    @Transactional
    fun metadata(token: String): TenantRegistrationInviteMetadataResponse {
        rateLimitService.checkTokenFailureAllowed("tenant-registration-invite", publicRateLimitTarget(token))
        val invite = try {
            requireValidInviteForCurrentDomain(token, lockForUpdate = false)
        } catch (exception: NotFoundException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", publicRateLimitTarget(token))
            throw exception
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", publicRateLimitTarget(token))
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", publicRateLimitTarget(token))
            throw exception
        }
        rateLimitService.recordTokenSuccess("tenant-registration-invite", publicRateLimitTarget(token))

        val tenant = invite.tenant
        return TenantRegistrationInviteMetadataResponse(
            tenantId = tenant.id,
            tenantName = tenant.name,
            tenantDisplayName = tenant.displayName,
            tenantDomain = tenant.domain,
            tenantLogoUrl = tenant.inviteLogoUrl(token),
            expiresAt = invite.expiresAt.atOffset(ZoneOffset.UTC),
            requiresLogin = requestContext.user != null,
        )
    }

    fun logo(token: String): File {
        rateLimitService.checkTokenFailureAllowed("tenant-registration-invite", publicRateLimitTarget(token))
        val invite = try {
            requireValidInviteForCurrentDomain(token, lockForUpdate = false)
        } catch (exception: NotFoundException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", publicRateLimitTarget(token))
            throw exception
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", publicRateLimitTarget(token))
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", publicRateLimitTarget(token))
            throw exception
        }
        rateLimitService.recordTokenSuccess("tenant-registration-invite", publicRateLimitTarget(token))
        return tenantService.tenantLogo(invite.tenant)
    }

    @Transactional
    fun join(token: String, request: JoinTenantRegistrationInviteRequest): LoginContext {
        val target = publicRateLimitTarget(token)
        rateLimitService.checkTokenFailureAllowed("tenant-registration-invite", target)
        val invite = try {
            requireValidInviteForCurrentDomain(token, lockForUpdate = true)
        } catch (exception: NotFoundException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", target)
            throw exception
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", target)
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", target)
            throw exception
        }

        val email = normalizeEmail(request.email)
        val existingUser = userRepo.findByEmail(email)
        val user = if (existingUser == null) {
            if (requestContext.user != null) {
                throw ConflictException("Logged in users must join with their existing account email")
            }
            createUserFromInvite(request, email)
        } else {
            requireLoggedInExistingUser(existingUser, email)
        }

        val joined = activateTenantMember(invite.tenant, user)
        if (joined) {
            invite.registrationCount += 1
        }
        rateLimitService.recordTokenSuccess("tenant-registration-invite", target)
        return authService.loginUser(user)
    }

    @Transactional
    fun passkeyOptions(token: String, request: TenantRegistrationInvitePasskeyOptionsRequest): String {
        requireValidInviteForCurrentDomain(token, lockForUpdate = false)
        val email = normalizeEmail(request.email)
        val displayName = normalizeDisplayName(request.displayName)
        val user = stagedPasskeyUser(email, displayName)
        return passkeyService.registrationOptionsForUser(user, routingContext)
    }

    @Transactional
    fun joinWithPasskey(token: String, request: JoinTenantRegistrationInviteWithPasskeyRequest): LoginContext {
        val target = publicRateLimitTarget(token)
        rateLimitService.checkTokenFailureAllowed("tenant-registration-invite", target)
        val invite = try {
            requireValidInviteForCurrentDomain(token, lockForUpdate = true)
        } catch (exception: NotFoundException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", target)
            throw exception
        } catch (exception: ForbiddenException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", target)
            throw exception
        } catch (exception: BadRequestException) {
            rateLimitService.recordTokenFailure("tenant-registration-invite", target)
            throw exception
        }

        val email = normalizeEmail(request.email)
        val displayName = normalizeDisplayName(request.displayName)
        val user = stagedPasskeyUser(email, displayName)
        passkeyService.createPasskeyForUser(user, request.passkeyDisplayName, request.credential, routingContext)
        capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(user)
        val joined = activateTenantMember(invite.tenant, user)
        if (joined) {
            invite.registrationCount += 1
        }
        rateLimitService.recordTokenSuccess("tenant-registration-invite", target)
        return authService.loginUser(user)
    }

    private fun requireValidInviteForCurrentDomain(token: String, lockForUpdate: Boolean): TenantRegistrationInvite {
        val tokenHash = HashingService.sha256(token)
        val invite = if (lockForUpdate) {
            tenantRegistrationInviteRepo.findByTokenHashForUpdate(tokenHash)
        } else {
            tenantRegistrationInviteRepo.findByTokenHash(tokenHash)
        } ?: throw NotFoundException("Tenant registration invite not found")

        val currentDomain = passkeyHostService.currentDomain(routingContext)
        if (invite.tenant.domain != currentDomain && !isLocalhostRequest(currentDomain)) {
            throw ForbiddenException("Tenant registration invite is not valid for this domain")
        }
        if (invite.tenant.activatedAt == null) {
            throw ForbiddenException("Tenant is not active")
        }
        if (invite.status(Instant.now()) != TenantRegistrationInviteStatus.Active) {
            throw BadRequestException("Tenant registration invite is no longer valid")
        }
        return invite
    }

    private fun createUserFromInvite(request: JoinTenantRegistrationInviteRequest, email: String): User {
        val displayName = normalizeDisplayName(request.displayName)
        val rawPassword = request.password?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("password must not be blank")
        val now = Instant.now()
        val user = User().also {
            it.email = email
            it.displayName = displayName
            it.permalink = uniquePermalink(displayName)
            it.role = ServerRole.ServerMember
            it.passwordHash = passwordService.randomPasswordHash()
            it.passwordSetAt = null
            userRepo.persist(it)
            userRepo.flush()
        }
        passwordService.setPassword(user, rawPassword, now)
        capabilityAcknowledgementService.acknowledgeCurrentAnnouncements(user)
        return user
    }

    private fun stagedPasskeyUser(email: String, displayName: String): User {
        val existingUser = userRepo.findByEmail(email)
        if (existingUser != null) {
            if (existingUser.disabledAt != null || existingUser.deletedAt != null || existingUser.systemUser) {
                throw UnauthorizedException("User cannot join this tenant")
            }
            if (userHasLogin(existingUser)) {
                throw ConflictException("Existing users must be logged in to join this tenant")
            }
            existingUser.displayName = displayName
            if (existingUser.permalink.startsWith("user-")) {
                existingUser.permalink = uniquePermalink(displayName)
            }
            return existingUser
        }
        return createStagedPasskeyUser(email, displayName)
    }

    private fun createStagedPasskeyUser(email: String, displayName: String): User =
        User().also {
            it.email = email
            it.displayName = displayName
            it.permalink = uniquePermalink(displayName)
            it.role = ServerRole.ServerMember
            it.passwordHash = passwordService.randomPasswordHash()
            it.passwordSetAt = null
            userRepo.persist(it)
            userRepo.flush()
        }

    private fun userHasLogin(user: User): Boolean =
        user.passwordSetAt != null || passkeyCredentialRepo.countByUser(user) > 0

    private fun requireLoggedInExistingUser(existingUser: User, email: String): User {
        if (existingUser.disabledAt != null || existingUser.deletedAt != null || existingUser.systemUser) {
            throw UnauthorizedException("User cannot join this tenant")
        }
        val currentUser = requestContext.user
            ?: throw UnauthorizedException("Existing users must be logged in to join this tenant")
        if (currentUser.id != existingUser.id) {
            throw ConflictException("You are already logged in with another user")
        }
        if (currentUser.email != email) {
            throw ConflictException("Logged in user does not match invite email")
        }
        return existingUser
    }

    private fun activateTenantMember(tenant: Tenant, user: User): Boolean {
        val now = Instant.now()
        tenantMemberRepo.findByTenantAndUser(tenant, user)?.let { membership ->
            if (membership.status == TenantMemberStatus.Active) {
                return false
            }
            membership.role = TenantRole.TenantMember
            membership.status = TenantMemberStatus.Active
            membership.acceptedAt = now
            membership.removedAt = null
            membership.removedBy = null
            return true
        }

        TenantMember().also {
            it.id = TenantMemberId(tenant.id, user.id)
            it.tenant = tenant
            it.user = user
            it.role = TenantRole.TenantMember
            it.acceptedAt = now
            it.status = TenantMemberStatus.Active
            tenantMemberRepo.persist(it)
        }
        return true
    }

    private fun TenantRegistrationInvite.toSummary(now: Instant): TenantRegistrationInviteSummary =
        TenantRegistrationInviteSummary(
            id = id,
            tenantId = tenant.id,
            tenantName = tenant.name,
            createdByUserId = createdBy.id,
            joinUrl = joinUrl(),
            expiresAt = expiresAt.atOffset(ZoneOffset.UTC),
            revokedAt = revokedAt?.atOffset(ZoneOffset.UTC),
            maxRegistrations = maxRegistrations,
            registrationCount = registrationCount,
            createdAt = createdAt.atOffset(ZoneOffset.UTC),
            status = status(now),
        )

    private fun TenantRegistrationInvite.joinUrl(): String =
        "${joinUrlBase(tenant)}/join#$token"

    private fun Tenant.inviteLogoUrl(token: String): String? =
        logoUpdatedAt
            ?.takeIf { logoStorageKey != null }
            ?.let { "/api/tenant-registration-invites/$token/logo?v=${URLEncoder.encode(it.toString(), Charsets.UTF_8)}" }

    private fun joinUrlBase(tenant: Tenant): String {
        val currentDomain = passkeyHostService.currentDomain(routingContext)
        if (isLocalhostRequest(currentDomain)) {
            return veyqConfig.publicWebBaseUrl
                .map { it.trim().removeSuffix("/") }
                .filter { it.isNotBlank() }
                .orElseGet { localRequestOrigin() }
        }
        return "https://${tenant.domain}"
    }

    private fun localRequestOrigin(): String {
        val uri = URI.create(routingContext.request().absoluteURI())
        return "${uri.scheme}://${uri.authority}".removeSuffix("/")
    }

    private fun isLocalhostRequest(domain: String): Boolean {
        if (!passkeyHostService.isLocalhostDomain(domain)) return false
        val remoteHost = routingContext.request().remoteAddress()?.host()?.lowercase() ?: return false
        return passkeyHostService.isLocalhostDomain(remoteHost)
    }

    private fun TenantRegistrationInvite.status(now: Instant): TenantRegistrationInviteStatus =
        when {
            revokedAt != null -> TenantRegistrationInviteStatus.Revoked
            !expiresAt.isAfter(now) -> TenantRegistrationInviteStatus.Expired
            maxRegistrations != null && registrationCount >= maxRegistrations!! -> TenantRegistrationInviteStatus.Exhausted
            else -> TenantRegistrationInviteStatus.Active
        }

    private fun normalizeEmail(email: String?): String =
        email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("email must not be blank")

    private fun normalizeDisplayName(displayName: String?): String =
        displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("displayName must not be blank")

    private fun uniquePermalink(displayName: String): String {
        val base = Permalinks.normalize(displayName).takeIf { it.isNotBlank() && it != "me" } ?: "user"
        var candidate = base
        var suffix = 2
        while (userRepo.findByPermalink(candidate) != null) {
            val nextSuffix = "-${suffix++}"
            candidate = base.take(120 - nextSuffix.length).trimEnd('-') + nextSuffix
        }
        return candidate
    }

    private fun publicRateLimitTarget(token: String): String =
        HashingService.sha256(token).take(16)

    private fun tenant(): Tenant {
        val tenantName = requestContext.requireTenant().name
        return tenantRepo.findByName(tenantName) ?: throw NotFoundException("Tenant not found")
    }

    private fun user(): User = requestContext.requireUser()

    private fun newInviteToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
