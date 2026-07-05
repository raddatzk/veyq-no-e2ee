package com.veyq.app.features.spaces.scope

import com.veyq.app.features.admin.tenant.MembershipEntity
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
}

/** Outcome of listing the caller's scopes. */
sealed interface ListScopesOutcome {
    data class Scopes(val rows: List<ScopeRow>) : ListScopesOutcome
    object NoTenant : ListScopesOutcome
}

/** Outcome of adding a member to a group by username (cr-0086). Admission only — the key is delivered later. */
sealed interface AddGroupMemberOutcome {
    data class Added(val row: ScopeMemberRow) : AddGroupMemberOutcome
    object ScopeNotFound : AddGroupMemberOutcome
    object NotOwner : AddGroupMemberOutcome
    object MemberNotFound : AddGroupMemberOutcome
    object AlreadyMember : AddGroupMemberOutcome
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
class ScopesHandler(private val store: ScopesStore) {

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
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return CreateGroupOutcome.NoTenant
        if (!store.isMember(userId, tenantId)) return CreateGroupOutcome.NoTenant
        return CreateGroupOutcome.Created(store.createGroup(userId, tenantId, encryptedName, wrappedGrant))
    }

    fun listMine(userId: String, host: String?): ListScopesOutcome {
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return ListScopesOutcome.NoTenant
        return ListScopesOutcome.Scopes(store.listMine(userId, tenantId))
    }

    /**
     * Add an existing tenant member to a **group** by username (en.spaces.add-group-member, cr-0086). Admission
     * is owner-gated ([ADR 0014](0014) §4 — only a group OWNER; the tenant owner has no say); the username is
     * resolved **within the scope's own tenant** (ADR 0020 — never a global search). Records a **grant-less**
     * MEMBER membership — no key crosses the wire; grant-delivery wraps `K_SC` to them afterwards (cr-0076). The
     * returned row has its `email` nulled (the add response never exposes it).
     */
    fun addGroupMember(callerId: String, scopeId: String, username: String): AddGroupMemberOutcome {
        if (store.scopeKind(scopeId) != ScopeEntity.GROUP) return AddGroupMemberOutcome.ScopeNotFound
        if (!store.isScopeOwner(callerId, scopeId)) return AddGroupMemberOutcome.NotOwner
        val tenantId = store.scopeTenantId(scopeId) ?: return AddGroupMemberOutcome.ScopeNotFound
        val targetId = store.resolveUsernameInTenant(tenantId, username) ?: return AddGroupMemberOutcome.MemberNotFound
        if (!store.isMember(targetId, tenantId)) return AddGroupMemberOutcome.MemberNotFound
        if (store.isScopeMember(targetId, scopeId)) return AddGroupMemberOutcome.AlreadyMember
        store.addGrantlessMember(targetId, tenantId, scopeId)
        val row = store.scopeMemberRow(scopeId, targetId) ?: return AddGroupMemberOutcome.MemberNotFound
        return AddGroupMemberOutcome.Added(row.copy(email = null))
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
        val owner = store.isTenantOwner(userId, tenantId)
        val rows = store.listMembers(scopeId).map { if (owner) it else it.copy(email = null) }
        return ListMembersOutcome.Members(rows)
    }
}
