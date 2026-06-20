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
import com.veyq.app.generated.model.UploadTenantLogoRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.config.TenantConfig
import com.veyq.app.shared.content.ContentPurge
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.identity.ServerRole
import com.veyq.app.shared.identity.User
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrant
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeKind
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.security.TenantAdminGuard
import com.veyq.app.shared.tenancy.GroupMembershipRepository
import com.veyq.app.shared.tenancy.InviteKind
import com.veyq.app.shared.tenancy.InviteStatus
import com.veyq.app.shared.tenancy.Membership
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.MembershipStatus
import com.veyq.app.shared.tenancy.MemberRole
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantInvite
import com.veyq.app.shared.tenancy.TenantInviteRepository
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.tenancy.TenantStatus
import com.veyq.app.shared.storage.BlobStore
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
import com.veyq.app.platform.error.notFound
import com.veyq.app.platform.error.unauthorized

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
    private val groupMembers: GroupMembershipRepository,
    private val purge: ContentPurge,
    private val sessions: SessionService,
    private val guard: TenantAdminGuard,
    private val suites: CryptoSuiteRegistry,
    private val store: BlobStore,
    tenantCfg: TenantConfig,
) {
    private val deletionGrace: Duration = tenantCfg.deletionGrace()

    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val rng = SecureRandom()
    private val inviteTtl: Duration = Duration.ofDays(7)
    private val logoUrlTtlSeconds = 900
    private val maxLogoBytes = 512 * 1024

    @Transactional
    fun createTenant(auth: String?, req: CreateTenantRequest): TenantSummary {
        val owner = requireServerOwner(auth)
        if (tenants.findByName(req.name) != null) {
            throw ApiException(409, "TENANT_EXISTS", "A tenant with that name already exists")
        }
        // A tenant is domain-scoped: the host resolves the tenant, so the domain is required + unique.
        val domain = req.domain.trim().lowercase()
        if (domain.isEmpty()) throw ApiException(400, "DOMAIN_REQUIRED", "A tenant domain is required")
        if (tenants.findByDomain(domain) != null) throw ApiException(409, "DOMAIN_TAKEN", "That domain is already in use")
        val tenant = Tenant().apply {
            id = newId(); name = req.name; displayName = req.displayName; this.domain = domain
            status = TenantStatus.PENDING; createdBy = owner.id; createdAt = Instant.now()
        }
        tenants.persist(tenant)
        return tenant.toSummary()
    }

    /**
     * Schedule a tenant for deletion after a grace period. Tenant Owner only. The tenant stays fully usable
     * until the deadline so members can secure their data; the deadline rides the roster (in-app warning),
     * the owner can cancel, and an hourly GC hard-purges it once the deadline passes. Confirm-by-name is
     * enforced client-side. Idempotent (re-scheduling just keeps the existing deadline).
     */
    @Transactional
    fun deleteTenant(auth: String?, tenantId: String) {
        val tenant = requireTenantOwner(auth, tenantId)
        if (tenant.scheduledDeletionAt == null) tenant.scheduledDeletionAt = Instant.now().plus(deletionGrace)
    }

    /** Owner cancels a scheduled deletion before the deadline — the tenant returns to normal. */
    @Transactional
    fun cancelTenantDeletion(auth: String?, tenantId: String) {
        requireTenantOwner(auth, tenantId).scheduledDeletionAt = null
    }

    /** Owner uploads scaled public tenant branding bytes into the configured BlobStore/S3 backend. */
    @Transactional
    fun uploadTenantLogo(auth: String?, tenantId: String, req: UploadTenantLogoRequest): TenantSummary {
        val tenant = requireTenantOwner(auth, tenantId)
        val contentType = req.contentType.value
        if (req.bytes.isEmpty()) throw ApiException(400, "LOGO_EMPTY", "Logo image is empty")
        if (req.bytes.size > maxLogoBytes) throw ApiException(400, "LOGO_TOO_LARGE", "Logo image is too large")
        val oldKey = tenant.logoObjectKey
        val key = "tenant-logos/${tenant.id}/${newId()}"
        store.put(key, req.bytes, contentType)
        tenant.logoObjectKey = key
        tenant.logoContentType = contentType
        oldKey?.let { runCatching { store.delete(it) } }
        return tenant.toSummary()
    }

    @Transactional
    fun clearTenantLogo(auth: String?, tenantId: String): TenantSummary {
        val tenant = requireTenantOwner(auth, tenantId)
        val oldKey = tenant.logoObjectKey
        tenant.logoObjectKey = null
        tenant.logoContentType = null
        oldKey?.let { runCatching { store.delete(it) } }
        return tenant.toSummary()
    }

    @Transactional
    fun getTenantLogo(tenantId: String): TenantLogoBytes {
        val tenant = tenants.findById(tenantId) ?: throw notFound("Tenant")
        val key = tenant.logoObjectKey ?: throw notFound("Tenant logo")
        val bytes = store.get(key) ?: throw notFound("Tenant logo")
        return TenantLogoBytes(contentType = tenant.logoContentType ?: "application/octet-stream", bytes = bytes)
    }

    /** The tenants whose grace period has passed — a read, so the scheduler can purge them one tx each. */
    @Transactional
    fun dueTenantIds(now: Instant): List<String> = tenants.listDue(now).map { it.id }

    /**
     * Hard-purge ONE tenant in its own transaction: cascade the tenant scope + every group scope, plus
     * memberships + invites, then the tenant row. Per-tenant so a backlog can't become one giant write
     * transaction (long locks, all-or-nothing rollback); a failed tenant is skipped, not poisoning the rest.
     */
    @Transactional
    fun purgeTenant(tenantId: String) {
        val tenant = tenants.findById(tenantId) ?: return
        scopes.listByTenantId(tenant.id).forEach { scope ->
            groupMembers.deleteByScopeId(scope.id) // no-op for the tenant root
            purge.purgeScope(scope.id)
        }
        memberships.deleteByTenantId(tenant.id)
        invites.deleteByTenantId(tenant.id)
        tenants.delete(tenant)
    }

    private fun requireTenantOwner(auth: String?, tenantId: String): Tenant {
        guard.requireOwner(auth, tenantId)
        return tenants.findById(tenantId) ?: throw notFound("Tenant")
    }

    @Transactional
    fun issueOwnerInvite(auth: String?, tenantId: String): OwnerInvite {
        val owner = requireServerOwner(auth)
        val tenant = tenants.findById(tenantId) ?: throw notFound("Tenant")
        val secret = ByteArray(32).also { rng.nextBytes(it) }
        val now = Instant.now()
        val expires = now.plus(inviteTtl)
        val invite = TenantInvite().apply {
            id = newId(); this.tenantId = tenant.id; role = MemberRole.Owner
            secretHash = sha256(secret); status = InviteStatus.PENDING
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
            role = invite.role.name,
            status = TenantInviteInfo.Status.valueOf(invite.status.name),
            kind = TenantInviteInfo.Kind.valueOf(invite.kind.name),
            scopePublicKey = scope?.let { KemPublicKey(suiteId = it.suiteId, kemPublicKey = enc.encodeToString(it.kemPublicKey)) },
        )
    }

    /** Release the temp-wrapped onboarding material — only to a caller who proves the bootstrap secret. */
    @Transactional
    fun getInviteMaterial(inviteId: String, req: GetInviteMaterialRequest): TenantInviteMaterial {
        val invite = invites.findById(inviteId) ?: throw notFound("Invite")
        if (invite.status != InviteStatus.PENDING) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (invite.kind != InviteKind.MEMBER) {
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
        if (invite.status != InviteStatus.PENDING) throw ApiException(409, "INVITE_NOT_PENDING", "Invite is not claimable")
        if (invite.expiresAt.isBefore(Instant.now())) throw ApiException(409, "INVITE_EXPIRED", "Invite has expired")
        if (!MessageDigest.isEqual(sha256(dec.decode(req.bootstrapSecret)), invite.secretHash)) {
            throw ApiException(400, "BAD_BOOTSTRAP_SECRET", "Invalid bootstrap secret")
        }
        suites.require(req.scopePublicKey.suiteId)
        suites.require(req.wrappedScopePrivateKey.suiteId)

        val tenant = tenants.findById(invite.tenantId) ?: throw notFound("Tenant")
        if (scopes.findByTenantId(tenant.id) != null) throw ApiException(409, "TENANT_ALREADY_CLAIMED", "Tenant already has an owner")
        val now = Instant.now()

        val scope = Scope().apply {
            id = newId(); tenantId = tenant.id; kind = ScopeKind.TENANT_ROOT
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
                id = newId(); tenantId = tenant.id; userId = user.id; role = MemberRole.Owner; status = MembershipStatus.ACTIVE; createdAt = now
            },
        )

        tenant.status = TenantStatus.ACTIVE
        invite.status = InviteStatus.CONSUMED
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
        TenantSummary(id = id, name = name, displayName = displayName, status = TenantSummary.Status.valueOf(status.name), domain = domain, logoUrl = logoUrl())

    private fun Tenant.logoUrl(): String? {
        val key = logoObjectKey ?: return null
        return store.presignGet(key, logoUrlTtlSeconds) ?: "/api/tenants/$id/logo?v=${key.substringAfterLast('/')}"
    }

    data class TenantLogoBytes(val contentType: String, val bytes: ByteArray)

    private fun requireUser(auth: String?): User {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        return users.findById(userId) ?: throw unauthorized()
    }

    private fun requireServerOwner(auth: String?): User {
        val user = requireUser(auth)
        if (user.serverRole != ServerRole.ServerOwner) throw ApiException(403, "FORBIDDEN", "Server Owner role required")
        return user
    }


    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun newId(): String = UUID.randomUUID().toString()
}
