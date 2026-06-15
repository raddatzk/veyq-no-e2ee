package com.veyq.app.features.admin.createtenantinviteowner

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.ClaimTenantOwnershipRequest
import com.veyq.app.generated.model.CreateTenantRequest
import com.veyq.app.generated.model.GetInviteMaterialRequest
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MyScopeGrant
import com.veyq.app.generated.model.OwnerInvite
import com.veyq.app.generated.model.TenantInviteInfo
import com.veyq.app.generated.model.TenantInviteMaterial
import com.veyq.app.generated.model.TenantSummary
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.content.ContentPurge
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantInvite
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.tenancy.TenantStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * uc.admin.create-tenant-invite-owner — the Server Owner provisions a tenant shell and an owner-claim
 * invitation; an existing user claims it and becomes the first key-holder. The operator never holds a
 * tenant key: it stores only the scope public key and the scope private key wrapped to the claimer.
 */
@ApplicationScoped
class TenantProvisioningHandler(
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val invites: TenantInviteRepository,
    private val memberships: MembershipRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val purge: ContentPurge,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val rng = SecureRandom()
    private val inviteTtl: Duration = Duration.ofDays(7)

    @Transactional
    fun createTenant(auth: String?, req: CreateTenantRequest): TenantSummary {
        val owner = requireServerOwner(auth)
        if (tenants.findByName(req.name) != null) {
            throw ApiException(409, "TENANT_EXISTS", "A tenant with that name already exists")
        }
        val tenant = Tenant().apply {
            id = newId(); name = req.name; displayName = req.displayName; domain = req.domain
            status = TenantStatus.PENDING.name; createdBy = owner.id; createdAt = Instant.now()
        }
        tenants.persist(tenant)
        return tenant.toSummary()
    }

    /**
     * Delete a group (tenant) and everything it owns. Tenant Owner only. Cascades the tenant scope (all
     * nodes + their media/journal/tracks/shares, the content key, items, grants) plus memberships and
     * invites, then the tenant row. The confirm-by-name gate is enforced client-side. Irreversible.
     */
    @Transactional
    fun deleteTenant(auth: String?, tenantId: String) {
        val callerId = sessions.resolve(auth) ?: throw ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
        val tenant = tenants.findById(tenantId) ?: throw notFound("Tenant")
        val caller = memberships.find(tenantId, callerId)
        if (caller == null || caller.role != "Owner" || caller.status != "ACTIVE") {
            throw ApiException(403, "FORBIDDEN", "Tenant Owner role required")
        }
        scopes.findByTenantId(tenantId)?.let { purge.purgeScope(it.id) }
        memberships.deleteByTenantId(tenantId)
        invites.deleteByTenantId(tenantId)
        tenants.delete(tenant)
    }

    @Transactional
    fun issueOwnerInvite(auth: String?, tenantId: String): OwnerInvite {
        val owner = requireServerOwner(auth)
        val tenant = tenants.findById(tenantId) ?: throw notFound("Tenant")
        val secret = ByteArray(32).also { rng.nextBytes(it) }
        val now = Instant.now()
        val expires = now.plus(inviteTtl)
        val invite = TenantInvite().apply {
            id = newId(); this.tenantId = tenant.id; role = "Owner"
            secretHash = sha256(secret); status = InviteStatus.PENDING.name
            createdBy = owner.id; createdAt = now; expiresAt = expires
        }
        invites.persist(invite)
        return OwnerInvite(
            inviteId = invite.id,
            bootstrapSecret = enc.encodeToString(secret),
            expiresAt = OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
        )
    }

    @Transactional
    fun getTenantInvite(inviteId: String): TenantInviteInfo {
        val invite = invites.findById(inviteId) ?: throw notFound("Invite")
        val tenant = tenants.findById(invite.tenantId) ?: throw notFound("Tenant")
        // Public metadata only. The temp-wrapped scope grant is NOT exposed here — it is released by
        // getInviteMaterial on proof of the bootstrap secret, so an inviteId alone never yields ciphertext.
        val scope = invite.scopeId?.let { scopes.findById(it) }
        return TenantInviteInfo(
            inviteId = invite.id,
            tenant = tenant.toSummary(),
            role = invite.role,
            status = TenantInviteInfo.Status.valueOf(invite.status),
            kind = TenantInviteInfo.Kind.valueOf(invite.kind),
            scopePublicKey = scope?.let { KemPublicKey(suiteId = it.suiteId, kemPublicKey = enc.encodeToString(it.kemPublicKey)) },
        )
    }

    /** Release the temp-wrapped onboarding material — only to a caller who proves the bootstrap secret. */
    @Transactional
    fun getInviteMaterial(inviteId: String, req: GetInviteMaterialRequest): TenantInviteMaterial {
        val invite = invites.findById(inviteId) ?: throw notFound("Invite")
        if (invite.status != InviteStatus.PENDING.name) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (invite.kind != InviteKind.MEMBER.name) {
            throw ApiException(400, "NOT_A_MEMBER_INVITE", "Only member invites expose temp-wrapped onboarding material")
        }
        if (!MessageDigest.isEqual(sha256(dec.decode(req.bootstrapSecret)), invite.secretHash)) {
            throw ApiException(400, "BAD_BOOTSTRAP_SECRET", "Invalid bootstrap secret")
        }
        val tempPub = invite.tempPublicKey ?: throw notFound("Invite material")
        val tempWrapped = invite.tempWrappedScopePrivateKey ?: throw notFound("Invite material")
        val scope = invite.scopeId?.let { scopes.findById(it) }
        return TenantInviteMaterial(
            tempPublicKey = KemPublicKey(suiteId = invite.tempWrapSuiteId ?: (scope?.suiteId ?: ""), kemPublicKey = enc.encodeToString(tempPub)),
            tempWrappedScopePrivateKey = Blob(suiteId = invite.tempWrapSuiteId ?: "", bytes = enc.encodeToString(tempWrapped)),
        )
    }

    @Transactional
    fun claimTenantOwnership(auth: String?, inviteId: String, req: ClaimTenantOwnershipRequest): TenantSummary {
        val user = requireUser(auth)
        val invite = invites.findByIdForUpdate(inviteId) ?: throw notFound("Invite")
        if (invite.status != InviteStatus.PENDING.name) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (!MessageDigest.isEqual(sha256(dec.decode(req.bootstrapSecret)), invite.secretHash)) {
            throw ApiException(400, "BAD_BOOTSTRAP_SECRET", "Invalid bootstrap secret")
        }
        requireSuite(req.scopePublicKey.suiteId)
        requireSuite(req.wrappedScopePrivateKey.suiteId)

        val tenant = tenants.findById(invite.tenantId) ?: throw notFound("Tenant")
        if (scopes.findByTenantId(tenant.id) != null) throw ApiException(409, "TENANT_ALREADY_CLAIMED", "Tenant already has an owner")
        val now = Instant.now()

        val scope = Scope().apply {
            id = newId(); tenantId = tenant.id; kind = "TENANT_ROOT"
            suiteId = req.scopePublicKey.suiteId; kemPublicKey = dec.decode(req.scopePublicKey.kemPublicKey); createdAt = now
        }
        scopes.persist(scope)
        grants.persist(
            ScopeGrant().apply {
                id = newId(); scopeId = scope.id; userId = user.id
                suiteId = req.wrappedScopePrivateKey.suiteId; wrappedPrivateKey = dec.decode(req.wrappedScopePrivateKey.bytes); createdAt = now
            },
        )
        memberships.persist(
            Membership().apply {
                id = newId(); tenantId = tenant.id; userId = user.id; role = "Owner"; status = "ACTIVE"; createdAt = now
            },
        )

        tenant.status = TenantStatus.ACTIVE.name
        invite.status = InviteStatus.CONSUMED.name
        invite.consumedBy = user.id
        return tenant.toSummary()
    }

    @Transactional
    fun getMyScopeGrant(auth: String?, tenantId: String): MyScopeGrant {
        val user = requireUser(auth)
        val scope = scopes.findByTenantId(tenantId) ?: throw notFound("Scope")
        val grant = grants.find(scope.id, user.id) ?: throw notFound("Scope grant")
        return MyScopeGrant(
            scopeId = scope.id,
            scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
            wrappedScopePrivateKey = Blob(suiteId = grant.suiteId, bytes = enc.encodeToString(grant.wrappedPrivateKey)),
        )
    }

    private fun Tenant.toSummary(): TenantSummary =
        TenantSummary(id = id, name = name, displayName = displayName, status = TenantSummary.Status.valueOf(status), domain = domain)

    private fun requireUser(auth: String?): User {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        return users.findById(userId) ?: throw unauthorized()
    }

    private fun requireServerOwner(auth: String?): User {
        val user = requireUser(auth)
        if (user.serverRole != ServerRole.ServerOwner) throw ApiException(403, "FORBIDDEN", "Server Owner role required")
        return user
    }

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
    private fun notFound(what: String) = ApiException(404, "NOT_FOUND", "$what not found")
    private fun newId(): String = UUID.randomUUID().toString()
}
