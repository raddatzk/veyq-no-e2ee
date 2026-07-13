package com.veyq.app.features.spaces.scope

import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.platform.capabilities.CapabilityIds
import com.veyq.app.features.platform.capabilities.CapabilityRegistry
import com.veyq.app.features.sharing.SharingStore
import jakarta.enterprise.context.ApplicationScoped

/** Outcome of birthing a personal scope. */
sealed interface CreatePersonalOutcome {
    data class Created(val scopeId: String) : CreatePersonalOutcome
    object NoTenant : CreatePersonalOutcome
    object AlreadyExists : CreatePersonalOutcome
}

/** Outcome of creating a group scope. Group names are not unique, so there is no "already exists" case. */
sealed interface CreateGroupOutcome {
    data class Created(val scopeId: String) : CreateGroupOutcome
    object NoTenant : CreateGroupOutcome
    /** The `spaces.groups` capability is disabled instance-wide (ADR 0032) — a 422, a personal-only instance. */
    object CapabilityDisabled : CreateGroupOutcome
}

/** Outcome of listing the caller's scopes. */
sealed interface ListScopesOutcome {
    data class Scopes(val rows: List<ScopeRow>) : ListScopesOutcome
    object NoTenant : ListScopesOutcome
}

/** Outcome of adding a member to a group by username (cr-0086). Admission only — the key is delivered later.
 *  `ConsentRequired` (cr-0096): the target has consent on but no `sealedNameKey` was supplied. */
sealed interface AddGroupMemberOutcome {
    data class Added(val row: ScopeMemberRow) : AddGroupMemberOutcome
    object ScopeNotFound : AddGroupMemberOutcome
    object NotOwner : AddGroupMemberOutcome
    object MemberNotFound : AddGroupMemberOutcome
    object AlreadyMember : AddGroupMemberOutcome
    object ConsentRequired : AddGroupMemberOutcome
}

/** Outcome of resolving a per-tenant username to a group-add candidate (cr-0094) — records nothing. */
sealed interface ResolveCandidateOutcome {
    data class Ok(val row: ResolvedCandidateRow) : ResolveCandidateOutcome
    object ScopeNotFound : ResolveCandidateOutcome
    object NotOwner : ResolveCandidateOutcome
    object MemberNotFound : ResolveCandidateOutcome
}

/** Outcome of accepting a consent proposal (cr-0094). Decline is unconditionally idempotent, so it has no outcome. */
sealed interface AcceptInvitationOutcome {
    object Ok : AcceptInvitationOutcome
    object NotFound : AcceptInvitationOutcome
}

/** Outcome of listing the caller's pending group invitations (cr-0094). */
sealed interface ListInvitationsOutcome {
    data class Invitations(val rows: List<GroupInvitationRow>) : ListInvitationsOutcome
    object NoTenant : ListInvitationsOutcome
}

/** Outcome of listing a scope's members (the directory, cr-0077). */
/** Outcome of promote/demote (cr-0088). */
sealed interface SetRoleOutcome {
    data class Ok(val row: ScopeMemberRow) : SetRoleOutcome
    object ScopeNotFound : SetRoleOutcome
    object NotOwner : SetRoleOutcome
    object MemberNotFound : SetRoleOutcome
    object LastOwner : SetRoleOutcome
}

/** Outcome of removing a member (cr-0088). */
sealed interface RemoveMemberOutcome {
    object Ok : RemoveMemberOutcome
    object ScopeNotFound : RemoveMemberOutcome
    object NotOwner : RemoveMemberOutcome
    object MemberNotFound : RemoveMemberOutcome
    object LastOwner : RemoveMemberOutcome
}

/** Outcome of a rotation-commit (cr-0088). */
sealed interface RotateOutcome {
    data class Ok(val epoch: Int) : RotateOutcome
    object ScopeNotFound : RotateOutcome
    object NotOwner : RotateOutcome
}

/** Outcome of reading the epoch-chain links (cr-0088). */
sealed interface EpochsOutcome {
    data class Links(val links: List<Pair<Int, String>>) : EpochsOutcome
    object Forbidden : EpochsOutcome
    object ScopeNotFound : EpochsOutcome
}

/** Outcome of renaming a group (cr-0097). Owner-gated, GROUP-only, active-only, current-epoch-only. */
sealed interface RenameGroupOutcome {
    object Ok : RenameGroupOutcome
    object ScopeNotFound : RenameGroupOutcome
    object NotOwner : RenameGroupOutcome
    object PendingDeletion : RenameGroupOutcome
    object StaleEpoch : RenameGroupOutcome
}

/** Outcome of soft-deleting a group (cr-0090). */
sealed interface DeleteGroupOutcome {
    object Ok : DeleteGroupOutcome
    object ScopeNotFound : DeleteGroupOutcome
    object NotGroup : DeleteGroupOutcome
    object NotOwner : DeleteGroupOutcome
    object AlreadyDeleted : DeleteGroupOutcome
}

/** Outcome of restoring a soft-deleted group (cr-0090). */
sealed interface RestoreGroupOutcome {
    object Ok : RestoreGroupOutcome
    object ScopeNotFound : RestoreGroupOutcome
    object NotOwner : RestoreGroupOutcome
    object NotDeleted : RestoreGroupOutcome
}

sealed interface ListMembersOutcome {
    data class Members(val rows: List<ScopeMemberRow>) : ListMembersOutcome
    object Forbidden : ListMembersOutcome
}

/**
 * Use-case logic for a member's scopes (en.spaces.personal-scope, cr-0026). The active tenant is resolved
 * from the request Host; the caller must already belong to it (you get a personal scope in tenants you are a
 * member of). Birth is client-driven and operator-blind — the handler only persists the ciphertext grant.
 */
@ApplicationScoped
class ScopesHandler(
    private val store: ScopesStore,
    private val capabilities: CapabilityRegistry,
    private val shares: SharingStore,
) {

    fun createPersonal(userId: String, host: String?, wrappedGrant: String): CreatePersonalOutcome {
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return CreatePersonalOutcome.NoTenant
        if (!store.isMember(userId, tenantId)) return CreatePersonalOutcome.NoTenant
        if (store.personalScopeExists(userId, tenantId)) return CreatePersonalOutcome.AlreadyExists
        return CreatePersonalOutcome.Created(store.createPersonal(userId, tenantId, wrappedGrant))
    }

    /**
     * Create a named group scope in the active tenant (en.spaces.group-scope, cr-0032, ADR 0014). The caller
     * must belong to the tenant; birth is client-driven and operator-blind — the handler persists only the
     * ciphertext name + the ciphertext self-grant, never the scope key or the plaintext name.
     */
    fun createGroup(userId: String, host: String?, encryptedName: String, wrappedGrant: String): CreateGroupOutcome {
        // Instance policy first (ADR 0032): a personal-only instance disables group scopes → 422, enforced
        // server-side even if a stale client still offers the affordance.
        if (!capabilities.isActive(CapabilityIds.SPACES_GROUPS)) return CreateGroupOutcome.CapabilityDisabled
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return CreateGroupOutcome.NoTenant
        if (!store.isMember(userId, tenantId)) return CreateGroupOutcome.NoTenant
        return CreateGroupOutcome.Created(store.createGroup(userId, tenantId, encryptedName, wrappedGrant))
    }

    fun listMine(userId: String, host: String?): ListScopesOutcome {
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return ListScopesOutcome.NoTenant
        return ListScopesOutcome.Scopes(store.listMine(userId, tenantId))
    }

    /**
     * Rename a GROUP (uc.spaces.rename-group, cr-0097, ADR 0027). Owner-gated ([ADR 0014](0014) §4 — the name
     * authority); GROUP-only; rejected while pending-deletion (a name-write during the grace period). The client
     * sealed the new name under `K_name` of the **current** epoch and sends that epoch back; the server enforces
     * `nameEpoch == currentEpoch` (`StaleEpoch` on a concurrent rotation) so the name stays forward-secret, then
     * overwrites the row's ciphertext name + name-epoch. Name-only — no `K_SC` rotation, no re-grant. Operator-blind:
     * the server never sees a key or the plaintext name.
     */
    fun renameGroup(callerId: String, scopeId: String, encryptedName: String, nameEpoch: Int): RenameGroupOutcome {
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return RenameGroupOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return RenameGroupOutcome.NotOwner
        if (store.deletedAt(scopeId) != null) return RenameGroupOutcome.PendingDeletion
        if (store.currentEpoch(scopeId) != nameEpoch) return RenameGroupOutcome.StaleEpoch
        store.renameGroup(scopeId, encryptedName, nameEpoch)
        return RenameGroupOutcome.Ok
    }

    /**
     * Add an existing tenant member to a **group** by username (en.spaces.add-group-member, cr-0086). Admission
     * is owner-gated ([ADR 0014](0014) §4 — only a group OWNER; the tenant owner has no say); the username is
     * resolved **within the scope's own tenant** (ADR 0020 — never a global search). Records a **grant-less**
     * MEMBER membership — no key crosses the wire; grant-delivery wraps `K_SC` to them afterwards (cr-0076). The
     * returned row has its `email` nulled (the add response never exposes it).
     */
    fun addGroupMember(callerId: String, scopeId: String, username: String, sealedNameKey: String?): AddGroupMemberOutcome {
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return AddGroupMemberOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return AddGroupMemberOutcome.NotOwner
        val tenantId = store.scopeTenantId(scopeId) ?: return AddGroupMemberOutcome.ScopeNotFound
        val targetId = store.resolveUsernameInTenant(tenantId, username) ?: return AddGroupMemberOutcome.MemberNotFound
        if (!store.isMember(targetId, tenantId)) return AddGroupMemberOutcome.MemberNotFound
        if (store.isScopeMember(targetId, scopeId)) return AddGroupMemberOutcome.AlreadyMember
        // Consent-first (cr-0096, ADR 0027): if the target asked to be consulted, record a soft `pendingConsent`
        // membership grant-delivery skips — so `K_SC` is gated **before** the wrap — carrying the wrapped name-key
        // `K_name`. The owner must have derived + sealed it (learned consent is required from the prior resolve);
        // no name-key → ConsentRequired.
        if (store.requiresGroupAddConsent(targetId)) {
            if (sealedNameKey == null) return AddGroupMemberOutcome.ConsentRequired
            store.addSoftPendingMember(targetId, tenantId, scopeId, sealedNameKey, callerId)
        } else {
            store.addGrantlessMember(targetId, tenantId, scopeId)
        }
        val row = store.scopeMemberRow(scopeId, targetId) ?: return AddGroupMemberOutcome.MemberNotFound
        return AddGroupMemberOutcome.Added(row.copy(email = null))
    }

    /**
     * Resolve a per-tenant username to a group-add candidate (uc.spaces.consent-group-add, cr-0094) — records
     * nothing. Owner-gated (the same gate as the add). This gate is convenience, **not** a confidentiality barrier:
     * any tenant member can create a group and become its owner in O(1), so username → identity-public-key →
     * consent-flag are effectively enumerable by every tenant member. That is within the metadata model (per-tenant
     * usernames + identity pubkeys are tenant-visible, ADR 0023/0014 §5), not a leak — don't read the gate as one.
     * Returns the candidate's identity public key (to seal the derived group name-key to) + whether consent is required.
     */
    fun resolveGroupMember(callerId: String, scopeId: String, username: String): ResolveCandidateOutcome {
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return ResolveCandidateOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return ResolveCandidateOutcome.NotOwner
        val tenantId = store.scopeTenantId(scopeId) ?: return ResolveCandidateOutcome.ScopeNotFound
        val targetId = store.resolveUsernameInTenant(tenantId, username) ?: return ResolveCandidateOutcome.MemberNotFound
        if (!store.isMember(targetId, tenantId)) return ResolveCandidateOutcome.MemberNotFound
        val pubkey = store.identityPublicKeyOf(targetId) ?: return ResolveCandidateOutcome.MemberNotFound
        return ResolveCandidateOutcome.Ok(
            ResolvedCandidateRow(targetId, username.trim().lowercase(), pubkey, store.requiresGroupAddConsent(targetId)),
        )
    }

    /**
     * Accept a consent proposal (cr-0094): clear the caller's own `pendingConsent` flag, so it becomes an ordinary
     * grant-less membership and grant-delivery wraps `K_SC` to them. Invitee-gated (acts only on the caller's
     * membership); idempotent — an already-accepted membership is a no-op Ok; no membership at all is NotFound.
     */
    fun acceptInvitation(callerId: String, scopeId: String): AcceptInvitationOutcome =
        when {
            store.clearPendingConsent(callerId, scopeId) > 0 -> AcceptInvitationOutcome.Ok
            store.consentState(callerId, scopeId) == ConsentState.ACTIVE -> AcceptInvitationOutcome.Ok // already accepted
            else -> AcceptInvitationOutcome.NotFound
        }

    /**
     * Decline (invitee) / rescind (owner reuses this via member-remove) a consent proposal (cr-0094): delete the
     * caller's own soft membership. Nothing was wrapped, so **no rotation**; no `declined` marker is kept.
     * Idempotent — always succeeds (no open proposal is still a silent no-op, so it is no enumeration oracle).
     */
    fun declineInvitation(callerId: String, scopeId: String) {
        store.deletePendingMembership(callerId, scopeId)
    }

    /** The caller's pending group invitations in the active tenant (cr-0094) — the invitee's Accept/Decline view. */
    fun listInvitations(userId: String, host: String?): ListInvitationsOutcome {
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return ListInvitationsOutcome.NoTenant
        return ListInvitationsOutcome.Invitations(store.listInvitations(userId, tenantId))
    }

    /**
     * Promote a member to co-owner or demote a co-owner (uc.spaces.manage-group-members, cr-0088). Owner-gated;
     * the **last-owner guard** rejects demoting the last OWNER (ADR 0007). A pure role change — no key moves.
     */
    fun setMemberRole(callerId: String, scopeId: String, targetId: String, role: String): SetRoleOutcome {
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return SetRoleOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return SetRoleOutcome.NotOwner
        val current = store.memberRole(targetId, scopeId) ?: return SetRoleOutcome.MemberNotFound
        if (current == MembershipEntity.OWNER && role == MembershipEntity.MEMBER && store.ownerCount(scopeId) <= 1) {
            return SetRoleOutcome.LastOwner
        }
        store.setRole(targetId, scopeId, role)
        // Build the response from the role we just set — the same-request read-back can see a stale persistence
        // context (the DB is committed; the guard checks below run in fresh requests against committed data).
        val row = store.scopeMemberRow(scopeId, targetId) ?: return SetRoleOutcome.MemberNotFound
        return SetRoleOutcome.Ok(row.copy(role = role, email = null))
    }

    /**
     * Remove a member from a group (cr-0088) — the instant **layer-1** cut (delete the membership). Owner-gated;
     * the last OWNER cannot be removed. **Layer-2** forward-secrecy is the caller's follow-up rotation.
     */
    fun removeMember(callerId: String, scopeId: String, targetId: String): RemoveMemberOutcome {
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return RemoveMemberOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return RemoveMemberOutcome.NotOwner
        val current = store.memberRole(targetId, scopeId) ?: return RemoveMemberOutcome.MemberNotFound
        if (current == MembershipEntity.OWNER && store.ownerCount(scopeId) <= 1) return RemoveMemberOutcome.LastOwner
        store.removeMembership(targetId, scopeId)
        // cr-0104: soft-revoke the public shares this member created in the group — their links 404 now. (Layer-1;
        // the caller's follow-up rotation is the layer-2 forward-secrecy for the group's content, ADR 0024.)
        shares.deactivateSharesByOwnerInScope(targetId, scopeId)
        return RemoveMemberOutcome.Ok
    }

    /**
     * Commit a scope-key rotation (cr-0088, ADR 0024): bump the epoch, store the chain-link, re-grant the listed
     * remaining members. Owner-gated; the client minted the new epoch on-device (the server stores only ciphertext).
     */
    fun rotateScope(callerId: String, scopeId: String, wrappedPrev: String, grants: List<Pair<String, String>>): RotateOutcome {
        if (store.currentEpoch(scopeId) == null) return RotateOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return RotateOutcome.NotOwner
        return RotateOutcome.Ok(store.rotate(scopeId, wrappedPrev, grants))
    }

    /**
     * Soft-delete a group (uc.spaces.delete-group, cr-0090, ADR 0014 §6) — set the `deletedAt` marker so the
     * group enters its grace period (read-only + restorable). Owner-gated; GROUP-only; idempotent (a group already
     * pending-deletion is rejected). Memberships/grants are untouched, so a restore is trivial.
     */
    fun deleteGroup(callerId: String, scopeId: String): DeleteGroupOutcome {
        val kind = store.scopeKind(scopeId) ?: return DeleteGroupOutcome.ScopeNotFound
        if (kind != ScopeEntity.GROUP) return DeleteGroupOutcome.NotGroup
        if (!store.isScopeOwner(callerId, scopeId)) return DeleteGroupOutcome.NotOwner
        if (store.deletedAt(scopeId) != null) return DeleteGroupOutcome.AlreadyDeleted
        store.softDelete(scopeId, java.time.Instant.now())
        return DeleteGroupOutcome.Ok
    }

    /**
     * Restore a group from pending-deletion (cr-0090) — clear the marker, active again for everyone. Owner-gated;
     * `409` if the group is not pending-deletion; a hard-purged scope is simply gone (404 from `scopeKind`).
     */
    fun restoreGroup(callerId: String, scopeId: String): RestoreGroupOutcome {
        if (store.scopeKind(scopeId) == null) return RestoreGroupOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return RestoreGroupOutcome.NotOwner
        if (store.deletedAt(scopeId) == null) return RestoreGroupOutcome.NotDeleted
        store.restore(scopeId)
        return RestoreGroupOutcome.Ok
    }

    /** The scope's epoch-chain links — any key-holder in the scope may read them (cr-0088, ADR 0024). */
    fun listEpochs(callerId: String, scopeId: String): EpochsOutcome {
        if (store.currentEpoch(scopeId) == null) return EpochsOutcome.ScopeNotFound
        if (!store.holdsGrant(callerId, scopeId)) return EpochsOutcome.Forbidden
        return EpochsOutcome.Links(store.epochLinks(scopeId))
    }

    /** Every tenant the caller belongs to (Host-independent) — the space switcher. */
    fun myTenants(userId: String): List<TenantRow> = store.myTenants(userId)

    /**
     * The member directory of a scope (uc.spaces.add-member, ADR 0020). Only a **key-holder** in the scope may
     * read it (gating on holding a grant also means a non-member — or a bogus scope id — gets a uniform 403, so
     * scope existence is not enumerable). Returns each member's per-tenant identity (username + sealed name),
     * pubkey + grant-present; **email is owner-only** — nulled for a non-owner caller (ADR 0023).
     */
    fun listMembers(userId: String, scopeId: String): ListMembersOutcome {
        if (!store.holdsGrant(userId, scopeId)) return ListMembersOutcome.Forbidden
        val tenantId = store.scopeTenantId(scopeId) ?: return ListMembersOutcome.Forbidden
        val tenantOwner = store.isTenantOwner(userId, tenantId)
        // Pending consent proposals (cr-0094) are visible to **group owners** only — ordinary members see a proposed
        // person only once they accept. Email stays tenant-owner-only (ADR 0023), as before.
        val scopeOwner = store.isScopeOwner(userId, scopeId)
        val rows = store.listMembers(scopeId)
            .filter { scopeOwner || !it.pendingConsent }
            .map { if (tenantOwner) it else it.copy(email = null) }
        return ListMembersOutcome.Members(rows)
    }
}
