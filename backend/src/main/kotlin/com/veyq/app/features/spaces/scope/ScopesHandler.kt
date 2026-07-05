package com.veyq.app.features.spaces.scope

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

/** Outcome of listing a scope's members (the directory, cr-0077). */
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
