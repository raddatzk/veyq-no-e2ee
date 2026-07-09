package com.veyq.app.features.admin.tenant

import com.veyq.app.features.sharing.SharingStore
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.features.tasks.TasksStore
import com.veyq.app.shared.crypto.Tokens
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant

/** Result of a create-tenant attempt. */
sealed interface CreateTenantOutcome {
    data class Created(val tenantId: String, val expiresAt: Instant, val bootstrapSecret: String) : CreateTenantOutcome
    data object DomainTaken : CreateTenantOutcome
}

/** Result of a resend/reassign on a pending tenant's invite. */
sealed interface InviteOutcome {
    data class Done(val invite: InviteView) : InviteOutcome
    data object NotFound : InviteOutcome
    data object NotPending : InviteOutcome
}

/** Result of revoking a pending tenant. */
sealed interface RevokeOutcome {
    data object Done : RevokeOutcome
    data object NotFound : RevokeOutcome
    data object NotPending : RevokeOutcome
}

/** Result of a tenant owner inviting a member by email (cr-0077). */
sealed interface MemberInviteOutcome {
    data class Done(val invite: InviteView) : MemberInviteOutcome
    data object NotFound : MemberInviteOutcome
    data object NotOwner : MemberInviteOutcome
    data object NotActive : MemberInviteOutcome
    data object AlreadyMember : MemberInviteOutcome
}

/** Result of listing a tenant's pending member invites (cr-0082). */
sealed interface MemberInvitesOutcome {
    data class Listed(val invites: List<PendingInviteRow>) : MemberInvitesOutcome
    data object NotFound : MemberInvitesOutcome
    data object NotOwner : MemberInvitesOutcome
}

/** Result of re-issuing a pending member invite (cr-0082). */
sealed interface ReissueOutcome {
    data class Done(val invite: InviteView) : ReissueOutcome
    data object NotFound : ReissueOutcome        // no such tenant
    data object NotOwner : ReissueOutcome
    data object InviteNotFound : ReissueOutcome  // no such pending invite for this tenant
}

/** Result of revoking a pending member invite (cr-0082). */
sealed interface RevokeMemberInviteOutcome {
    data object Done : RevokeMemberInviteOutcome
    data object NotFound : RevokeMemberInviteOutcome
    data object NotOwner : RevokeMemberInviteOutcome
    data object InviteNotFound : RevokeMemberInviteOutcome
}

/** Result of a tenant owner removing a member — the ACL-revoke cascade + orphan rule (cr-0091). */
sealed interface RemoveTenantMemberOutcome {
    data object Ok : RemoveTenantMemberOutcome
    data object NotOwner : RemoveTenantMemberOutcome       // caller is not the tenant OWNER
    data object MemberNotFound : RemoveTenantMemberOutcome  // target holds no tenant-scope membership
    data object LastOwner : RemoveTenantMemberOutcome       // target is the last tenant OWNER
}

/**
 * Server-Owner tenant administration, structural half (uc.admin.create-tenant Slice A, cr-0009). Purely
 * fachlich: authentication + the Server-Owner gate are central (`@RolesAllowed` on [TenantsResource] →
 * `SecurityIdentity`, cr-0012), so the handler only sees already-authorized calls. It mints the hashed bootstrap
 * invite (only the hash is stored; the raw token goes to the [TenantInviteMailer] for the link) and keeps the
 * Resource a thin HTTP adapter (ADR 0001). No content crypto, no scope key — activation is Slice B. The caller's
 * identity is not recorded (Slice A tracks no `created_by`), so no userId is threaded in.
 */
@ApplicationScoped
class TenantHandler(
    private val store: TenantStore,
    private val scopes: ScopesStore,
    private val tasks: TasksStore,
    private val mailer: TenantInviteMailer,
    private val shares: SharingStore,
    @param:ConfigProperty(name = "veyq.tenant.invite.ttl", defaultValue = "P7D") private val inviteTtl: Duration,
    @param:ConfigProperty(name = "veyq.tasks.max-attempts", defaultValue = "5") private val maxAttempts: Int,
) {

    @Transactional
    fun create(name: String, domain: String, ownerEmail: String): CreateTenantOutcome {
        val normalizedDomain = domain.lowercase()
        val normalizedEmail = ownerEmail.lowercase()
        if (store.domainExists(normalizedDomain)) return CreateTenantOutcome.DomainTaken

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val (tenantId, expiry) = store.createPendingTenant(name, normalizedDomain, normalizedEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(normalizedDomain, normalizedEmail, token, expiry)
        // Return the raw token so the Server Owner can copy the invite link (works without a mailer, cr-0080).
        return CreateTenantOutcome.Created(tenantId, expiry, token)
    }

    @Transactional
    fun list(): List<TenantView> = store.listAll()

    /** Resend: re-mint to the SAME owner email. */
    @Transactional
    fun resendInvite(tenantId: String): InviteOutcome =
        onPendingTenant(tenantId) { tenant -> mintTo(tenant.id, tenant.ownerEmail, tenant.domain) }

    /** Reassign: re-bind to a NEW owner email, then re-mint. */
    @Transactional
    fun reassignInvite(tenantId: String, newOwnerEmail: String): InviteOutcome =
        onPendingTenant(tenantId) { tenant ->
            val email = newOwnerEmail.lowercase()
            store.updateOwnerEmail(tenant.id, email)
            mintTo(tenant.id, email, tenant.domain)
        }

    @Transactional
    fun revoke(tenantId: String): RevokeOutcome {
        val tenant = store.findTenant(tenantId) ?: return RevokeOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return RevokeOutcome.NotPending
        store.deleteTenant(tenantId)
        return RevokeOutcome.Done
    }

    /**
     * A tenant **owner** invites a person (by email) into the ACTIVE tenant as a MEMBER (cr-0077 / ADR 0020).
     * Admission is owner-gated (the caller must hold the tenant's OWNER membership); the invitee onboards via
     * the same cold-birth accept flow with the invite's MEMBER role. The scope key is delivered afterward by a
     * key-holder (cr-0076) — this only mints the admission token.
     */
    @Transactional
    fun inviteMember(callerId: String, tenantId: String, email: String): MemberInviteOutcome {
        val tenant = store.findTenant(tenantId) ?: return MemberInviteOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MemberInviteOutcome.NotOwner
        if (tenant.status != TenantEntity.ACTIVE) return MemberInviteOutcome.NotActive
        val normalizedEmail = email.lowercase()
        if (store.isEmailMember(tenantId, normalizedEmail)) return MemberInviteOutcome.AlreadyMember

        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        store.mintMemberInvite(tenantId, normalizedEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(tenant.domain, normalizedEmail, token, expiresAt)
        return MemberInviteOutcome.Done(InviteView(normalizedEmail, TenantInviteEntity.ACTIVE, expiresAt, token))
    }

    /** List a tenant's pending member invites — owner-gated (cr-0082). */
    @Transactional
    fun listMemberInvites(callerId: String, tenantId: String): MemberInvitesOutcome {
        store.findTenant(tenantId) ?: return MemberInvitesOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MemberInvitesOutcome.NotOwner
        return MemberInvitesOutcome.Listed(store.listPendingMemberInvites(tenantId))
    }

    /** Re-issue a pending member invite: supersede it + mint a fresh secret for the same email (cr-0082). */
    @Transactional
    fun reissueMemberInvite(callerId: String, tenantId: String, inviteId: String): ReissueOutcome {
        val tenant = store.findTenant(tenantId) ?: return ReissueOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return ReissueOutcome.NotOwner
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return ReissueOutcome.InviteNotFound
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        // mintMemberInvite supersedes the current ACTIVE invite for this email (i.e. the one we found) + mints anew.
        store.mintMemberInvite(tenantId, invite.ownerEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(tenant.domain, invite.ownerEmail, token, expiresAt)
        return ReissueOutcome.Done(InviteView(invite.ownerEmail, TenantInviteEntity.ACTIVE, expiresAt, token))
    }

    /** Revoke a pending member invite (soft-terminal, additive-safe) — owner-gated (cr-0082). */
    @Transactional
    fun revokeMemberInvite(callerId: String, tenantId: String, inviteId: String): RevokeMemberInviteOutcome {
        store.findTenant(tenantId) ?: return RevokeMemberInviteOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return RevokeMemberInviteOutcome.NotOwner
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return RevokeMemberInviteOutcome.InviteNotFound
        store.revokeInvite(invite.id)
        return RevokeMemberInviteOutcome.Done
    }

    /**
     * A tenant **owner** removes a member (uc.spaces.remove-tenant-member, ADR 0014 §6 / ADR 0025). Owner-gated;
     * the tenant **last-owner guard** rejects removing the last tenant OWNER (ADR 0007). Then the cascade, per
     * scope the target belongs to in this tenant:
     *  - **TENANT** + a **shared GROUP** → **delete** the membership (instant layer-1 cut on shared content; the
     *    tenant key is **not** rotated, ADR 0025 — ACL-revoke). A co-shared GROUP additionally **queues a
     *    `SCOPE_ROTATION` job** (cr-0092, Fork 1b) so the next online remaining key-holder rotates it forward for
     *    layer-2 forward secrecy — the removing owner need not hold that group's key.
     *  - a shared **GROUP the target solely owned** (OWNER, no other owner) → promote the **oldest** remaining
     *    member to OWNER (the orphan rule — a server rule, the tenant owner never gains access), then delete.
     *  - the target's **own** scopes — their **PERSONAL** scope + any **solo GROUP** (they are the only member) →
     *    **soft-delete** (cr-0090 grace + read-only + purge) but **keep** their membership, so they retain read-only
     *    access to read/export their own memories during grace before `ScopePurge` erases them.
     * Operator-blind: only membership deletions + a lifecycle timestamp — never a key.
     */
    @Transactional
    fun removeMember(callerId: String, tenantId: String, targetId: String): RemoveTenantMemberOutcome {
        if (!store.isOwner(callerId, tenantId)) return RemoveTenantMemberOutcome.NotOwner
        val memberships = scopes.membershipsInTenant(targetId, tenantId)
        val tenantMembership = memberships.firstOrNull { it.scopeId == tenantId }
            ?: return RemoveTenantMemberOutcome.MemberNotFound
        if (tenantMembership.role == MembershipEntity.OWNER && store.tenantOwnerCount(tenantId) <= 1) {
            return RemoveTenantMemberOutcome.LastOwner
        }
        val now = Instant.now()
        for (m in memberships) {
            when {
                // The target's own scopes: grace-tombstone, keep the membership (read-only grace, then purge).
                m.kind == ScopeEntity.PERSONAL -> scopes.softDelete(m.scopeId, now)
                m.kind == ScopeEntity.GROUP && m.memberCount <= 1 -> scopes.softDelete(m.scopeId, now)
                // Shared content: instant layer-1 cut. First hand over a group they solely owned (orphan rule).
                else -> {
                    if (m.kind == ScopeEntity.GROUP && m.role == MembershipEntity.OWNER &&
                        scopes.ownerCount(m.scopeId) <= 1
                    ) {
                        scopes.oldestOtherMember(m.scopeId, targetId)
                            ?.let { scopes.setRole(it, m.scopeId, MembershipEntity.OWNER) }
                    }
                    scopes.removeMembership(targetId, m.scopeId)
                    // cr-0104: soft-revoke the shares this member created in the shared scope they're removed from
                    // — their public links 404 now (their own personal/solo-group shares stay through the grace).
                    shares.deactivateSharesByOwnerInScope(targetId, m.scopeId)
                    // Layer-2 forward secrecy for a co-shared GROUP: the removing owner isn't necessarily a
                    // key-holder here, so queue an async rotation for the next online remaining key-holder to
                    // complete on-device (cr-0092, Fork 1b). Idempotent + coalescing. The TENANT scope is NOT
                    // rotated — ACL-revoke only (ADR 0025), so it falls through with no job.
                    if (m.kind == ScopeEntity.GROUP) tasks.originateScopeRotation(m.scopeId, maxAttempts)
                }
            }
        }
        return RemoveTenantMemberOutcome.Ok
    }

    private fun mintTo(tenantId: String, ownerEmail: String, domain: String): InviteView {
        val token = Tokens.random()
        val expiresAt = Instant.now().plus(inviteTtl)
        val invite = store.supersedeAndMintInvite(tenantId, ownerEmail, Tokens.hash(token), expiresAt)
        mailer.sendInvite(domain, ownerEmail, token, expiresAt)
        return invite.copy(bootstrapSecret = token)
    }

    private inline fun onPendingTenant(tenantId: String, block: (TenantEntity) -> InviteView): InviteOutcome {
        val tenant = store.findTenant(tenantId) ?: return InviteOutcome.NotFound
        if (tenant.status != TenantEntity.PENDING) return InviteOutcome.NotPending
        return InviteOutcome.Done(block(tenant))
    }
}
