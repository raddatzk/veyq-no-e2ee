package com.veyq.app.features.spaces.scope

import jakarta.enterprise.context.ApplicationScoped

/** Outcome of birthing a personal scope. */
sealed interface CreatePersonalOutcome {
    data class Created(val scopeId: String) : CreatePersonalOutcome
    object NoTenant : CreatePersonalOutcome
    object AlreadyExists : CreatePersonalOutcome
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

    fun listMine(userId: String, host: String?): ListScopesOutcome {
        val tenantId = host?.let { store.findTenantIdByDomain(it) } ?: return ListScopesOutcome.NoTenant
        return ListScopesOutcome.Scopes(store.listMine(userId, tenantId))
    }

    /** Every tenant the caller belongs to (Host-independent) — the space switcher. */
    fun myTenants(userId: String): List<TenantRow> = store.myTenants(userId)

    /**
     * The member directory of a scope (uc.spaces.add-member, ADR 0020). Only a **key-holder** in the scope may
     * read it (gating on holding a grant also means a non-member — or a bogus scope id — gets a uniform 403, so
     * scope existence is not enumerable). Returns operational metadata only (pubkeys/roles/grant-present).
     */
    fun listMembers(userId: String, scopeId: String): ListMembersOutcome =
        if (!store.holdsGrant(userId, scopeId)) ListMembersOutcome.Forbidden
        else ListMembersOutcome.Members(store.listMembers(scopeId))
}
