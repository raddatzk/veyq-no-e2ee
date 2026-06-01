package com.veyq.api.auth.passkey

import com.veyq.api.auth.AuthService
import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.LoginContext
import com.veyq.api.auth.UnauthorizedException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.domain.PasskeyLinkToken
import com.veyq.api.domain.PasskeyLinkTokenRepo
import com.veyq.api.domain.PasskeyCredentialRepo
import com.veyq.api.domain.TenantMemberStatus
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.User
import com.veyq.api.generated.model.CompletePasskeyLinkRequest
import com.veyq.api.generated.model.CreatePasskeyLinkRequest
import com.veyq.api.generated.model.ExchangePasskeyLinkCodeRequest
import com.veyq.api.generated.model.PasskeyLinkGrantResponse
import com.veyq.api.generated.model.PasskeyLinkOptionsRequest
import com.veyq.api.generated.model.PasskeyLinkResponse
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@ApplicationScoped
class PasskeyLinkService(
    private val requestContext: RequestContext,
    private val passkeyHostService: PasskeyHostService,
    private val passkeyLinkTokenRepo: PasskeyLinkTokenRepo,
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val passkeyCredentialRepo: PasskeyCredentialRepo,
    private val passkeyService: PasskeyService,
    private val authService: AuthService,
) {
    private val random = SecureRandom()

    @Transactional
    fun createLink(request: CreatePasskeyLinkRequest, routingContext: RoutingContext): PasskeyLinkResponse {
        val user = requestContext.user ?: throw UnauthorizedException("Authentication required")
        val authDomain = passkeyHostService.requireAllowedCurrentDomain(routingContext)
        val (targetOrigin, targetDomain) = passkeyHostService.normalizeOrigin(request.targetOrigin)
        passkeyHostService.requireAllowedDomain(targetDomain)
        if (authDomain == targetDomain) throw BadRequestException("Target domain must differ from auth domain")
        requireMembership(user, targetDomain)
        requireNoTargetPasskey(user, targetDomain)

        val rawCode = newToken()
        val redirectPath = normalizeRedirectPath(request.redirectPath)
        PasskeyLinkToken().also {
            it.codeHash = hash(rawCode)
            it.user = user
            it.authDomain = authDomain
            it.targetDomain = targetDomain
            it.redirectPath = redirectPath
            it.createdAt = Instant.now()
            it.expiresAt = it.createdAt.plus(TOKEN_TTL)
            it.persist()
        }

        return PasskeyLinkResponse(rawCode, targetOrigin, redirectPath)
    }

    @Transactional
    fun exchangeCode(request: ExchangePasskeyLinkCodeRequest, routingContext: RoutingContext): PasskeyLinkGrantResponse {
        val link = requireOpenCode(request.code, routingContext)
        val grant = newToken()
        link.grantHash = hash(grant)
        link.exchangedAt = Instant.now()
        return PasskeyLinkGrantResponse(grant, link.redirectPath)
    }

    fun registrationOptions(request: PasskeyLinkOptionsRequest, routingContext: RoutingContext): String {
        val link = requireOpenGrant(request.grant, routingContext)
        return passkeyService.registrationOptionsForUser(link.user, routingContext)
    }

    @Transactional
    fun complete(request: CompletePasskeyLinkRequest, routingContext: RoutingContext): LoginContext {
        val link = requireOpenGrant(request.grant, routingContext, lockForUpdate = true)
        val user = link.user
        passkeyService.createPasskeyForUser(user, request.displayName, request.credential, routingContext)
        link.consumedAt = Instant.now()
        return authService.loginUser(user)
    }

    private fun requireOpenCode(
        rawCode: String,
        routingContext: RoutingContext,
    ): PasskeyLinkToken {
        val codeHash = hash(rawCode)
        val link = passkeyLinkTokenRepo.findByCodeHashForUpdate(codeHash)
            ?: throw BadRequestException("Invalid passkey link")
        if (link.exchangedAt != null || link.grantHash != null) {
            throw BadRequestException("Invalid passkey link")
        }
        return requireOpenLink(link, routingContext)
    }

    private fun requireOpenGrant(
        rawGrant: String,
        routingContext: RoutingContext,
        lockForUpdate: Boolean = false,
    ): PasskeyLinkToken {
        val grantHash = hash(rawGrant)
        val link = if (lockForUpdate) {
            passkeyLinkTokenRepo.findByGrantHashForUpdate(grantHash)
        } else {
            passkeyLinkTokenRepo.findByGrantHash(grantHash)
        } ?: throw BadRequestException("Invalid passkey link")
        return requireOpenLink(link, routingContext)
    }

    private fun requireOpenLink(link: PasskeyLinkToken, routingContext: RoutingContext): PasskeyLinkToken {
        val now = Instant.now()
        if (link.consumedAt != null || !link.expiresAt.isAfter(now)) {
            throw BadRequestException("Invalid passkey link")
        }
        val currentDomain = passkeyHostService.requireAllowedCurrentDomain(routingContext)
        if (currentDomain != link.targetDomain) {
            throw ForbiddenException("Passkey link is not valid for this domain")
        }
        requireMembership(link.user, link.targetDomain)
        requireNoTargetPasskey(link.user, link.targetDomain)
        return link
    }

    private fun requireMembership(user: User, targetDomain: String) {
        val tenant = tenantRepo.findByDomain(targetDomain)
            ?: throw ForbiddenException("Target domain is not available")
        val membership = tenantMemberRepo.findByTenantAndUser(tenant, user)
            ?: throw ForbiddenException("User is not member of this tenant")
        if (membership.status != TenantMemberStatus.Active && membership.status != TenantMemberStatus.Pending) {
            throw ForbiddenException("User is not member of this tenant")
        }
    }

    private fun requireNoTargetPasskey(user: User, targetDomain: String) {
        if (passkeyCredentialRepo.countByUserAndRelyingPartyId(user, targetDomain) > 0) {
            throw ConflictException("User already has a passkey for this domain")
        }
    }

    private fun normalizeRedirectPath(value: String?): String {
        val path = value?.trim().takeUnless { it.isNullOrBlank() } ?: "/"
        if (!path.startsWith("/") || path.startsWith("//") || path.length > 1024) {
            throw BadRequestException("Invalid redirect path")
        }
        return path
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val TOKEN_TTL: Duration = Duration.ofMinutes(5)
    }
}
