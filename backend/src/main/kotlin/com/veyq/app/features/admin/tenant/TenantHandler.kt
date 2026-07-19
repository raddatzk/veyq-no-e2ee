package com.veyq.app.features.admin.tenant

import com.veyq.app.features.sharing.SharingStore
import com.veyq.app.features.spaces.scope.ScopesStore
import com.veyq.app.features.tasks.TasksStore
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.http.FailureStatus
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import com.veyq.app.shared.membership.MembershipEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/** Result of a create-tenant attempt. */
sealed interface CreateTenantOutcome {
    data class Created(val tenantId: String, val expiresAt: Instant, val bootstrapSecret: String) : CreateTenantOutcome
    data object DomainTaken : CreateTenantOutcome, HttpFailure by httpFailure("domain_taken", FailureStatus.CONFLICT)
}

/** Result of a resend/reassign on a pending tenant's invite. */
sealed interface InviteOutcome {
    data class Done(val invite: InviteView) : InviteOutcome
    data object NotFound : InviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotPending : InviteOutcome, HttpFailure by httpFailure("tenant_not_pending", FailureStatus.CONFLICT)
}

/** Result of revoking a pending tenant. */
sealed interface RevokeOutcome {
    data object Done : RevokeOutcome
    data object NotFound : RevokeOutcome, HttpFailure by Failures.tenantNotFound
    data object NotPending : RevokeOutcome, HttpFailure by httpFailure("tenant_not_pending", FailureStatus.CONFLICT)
}

/** Result of a tenant owner inviting a member by email (cr-0077). */
sealed interface MemberInviteOutcome {
    data class Done(val invite: InviteView) : MemberInviteOutcome
    data object NotFound : MemberInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MemberInviteOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object NotActive : MemberInviteOutcome, HttpFailure by httpFailure("tenant_not_active", FailureStatus.CONFLICT)
    data object AlreadyMember : MemberInviteOutcome, HttpFailure by Failures.alreadyMember
}

/** Result of listing a tenant's pending member invites (cr-0082). */
sealed interface MemberInvitesOutcome {
    data class Listed(val invites: List<PendingInviteRow>) : MemberInvitesOutcome
    data object NotFound : MemberInvitesOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : MemberInvitesOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/** Result of re-issuing a pending member invite (cr-0082). */
sealed interface ReissueOutcome {
    data class Done(val invite: InviteView) : ReissueOutcome
    data object NotFound : ReissueOutcome, HttpFailure by Failures.tenantNotFound        // no such tenant
    data object NotOwner : ReissueOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object InviteNotFound : ReissueOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)  // no such pending invite for this tenant
}

/** Result of revoking a pending member invite (cr-0082). */
sealed interface RevokeMemberInviteOutcome {
    data object Done : RevokeMemberInviteOutcome
    data object NotFound : RevokeMemberInviteOutcome, HttpFailure by Failures.tenantNotFound
    data object NotOwner : RevokeMemberInviteOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
    data object InviteNotFound : RevokeMemberInviteOutcome, HttpFailure by httpFailure("invite_not_found", FailureStatus.NOT_FOUND)
}

/** Result of a tenant owner removing a member — the ACL-revoke cascade + orphan rule (cr-0091). */
sealed interface RemoveTenantMemberOutcome {
    data object Ok : RemoveTenantMemberOutcome
    data object NotFound : RemoveTenantMemberOutcome, HttpFailure by Failures.tenantNotFound  // no such tenant, or the caller is not a member (indistinguishable)
    data object NotOwner : RemoveTenantMemberOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)       // caller is not the tenant OWNER
    data object MemberNotFound : RemoveTenantMemberOutcome, HttpFailure by Failures.memberNotFound  // target holds no tenant-scope membership
    data object LastOwner : RemoveTenantMemberOutcome, HttpFailure by Failures.lastOwner       // target is the last tenant OWNER
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
    config: VeyqConfig,
) {
    private val inviteTtl: Duration = config.tenant().invite().ttl()
    private val maxAttempts: Int = config.tasks().maxAttempts()

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
        if (!store.hasMembership(callerId, tenantId)) return MemberInviteOutcome.NotFound
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
        if (!store.hasMembership(callerId, tenantId)) return MemberInvitesOutcome.NotFound
        store.findTenant(tenantId) ?: return MemberInvitesOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return MemberInvitesOutcome.NotOwner
        return MemberInvitesOutcome.Listed(store.listPendingMemberInvites(tenantId))
    }

    /** Re-issue a pending member invite: supersede it + mint a fresh secret for the same email (cr-0082). */
    @Transactional
    fun reissueMemberInvite(callerId: String, tenantId: String, inviteId: String): ReissueOutcome {
        if (!store.hasMembership(callerId, tenantId)) return ReissueOutcome.NotFound
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
        if (!store.hasMembership(callerId, tenantId)) return RevokeMemberInviteOutcome.NotFound
        store.findTenant(tenantId) ?: return RevokeMemberInviteOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return RevokeMemberInviteOutcome.NotOwner
        val invite = store.findPendingMemberInvite(tenantId, inviteId) ?: return RevokeMemberInviteOutcome.InviteNotFound
        store.revokeInvite(invite.id)
        return RevokeMemberInviteOutcome.Done
    }

    /**
     * A tenant **owner** removes a member (uc.spaces.remove-tenant-member, ADR 0014 §6 / ADR 0025).
     * Membership-gated first (a non-member gets the same 404 as a bogus tenant id, ADR 0042), then owner-gated;
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
        if (!store.hasMembership(callerId, tenantId)) return RemoveTenantMemberOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return RemoveTenantMemberOutcome.NotOwner
        // Impure shell: load the directory (ownerCount pre-resolved per row), ask the pure policy, execute its steps.
        // Every branch and boundary of the decision lives in RemovalPolicy, where it is mutation-verified (ADR 0045 §2).
        val rows = scopes.membershipsInTenant(targetId, tenantId)
            .map { RemovalScope(it.scopeId, it.kind, it.role, it.memberCount, it.ownerCount) }
        return when (val plan = RemovalPolicy.plan(rows, tenantId)) {
            RemovalPlan.MemberNotFound -> RemoveTenantMemberOutcome.MemberNotFound
            RemovalPlan.LastOwner -> RemoveTenantMemberOutcome.LastOwner
            is RemovalPlan.Cascade -> {
                val now = Instant.now()
                plan.steps.forEach { execute(it, targetId, now) }
                RemoveTenantMemberOutcome.Ok
            }
        }
    }

    /** Run one policy-decided cascade step against the stores. Decides nothing — the `when` only dispatches. */
    private fun execute(step: CascadeStep, targetId: String, now: Instant) {
        when (step.action) {
            // The target's own scope: grace-tombstone, keep the membership (read-only grace, then purge).
            CascadeAction.SOFT_DELETE -> scopes.softDelete(step.scopeId, now)
            // Orphan rule: hand the solely-owned group to the oldest remaining member before cutting the target.
            CascadeAction.TRANSFER_THEN_REMOVE -> {
                scopes.oldestOtherMember(step.scopeId, targetId)
                    ?.let { scopes.setRole(it, step.scopeId, MembershipEntity.OWNER) }
                cutSharedAccess(step, targetId)
            }
            CascadeAction.REMOVE -> cutSharedAccess(step, targetId)
        }
    }

    /** The shared-access cut (layer-1): drop the membership, soft-revoke the shares the target created here
     *  (cr-0104), and queue the async forward rotation for a co-shared GROUP (cr-0092, Fork 1b) — the removing
     *  owner need not be a key-holder. The TENANT scope carries `rotateAfter = false` (ACL-revoke only, ADR 0025). */
    private fun cutSharedAccess(step: CascadeStep, targetId: String) {
        scopes.removeMembership(targetId, step.scopeId)
        shares.deactivateSharesByOwnerInScope(targetId, step.scopeId)
        if (step.rotateAfter) tasks.originateScopeRotation(step.scopeId, maxAttempts)
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
