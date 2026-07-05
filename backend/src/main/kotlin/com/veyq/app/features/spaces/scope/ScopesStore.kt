package com.veyq.app.features.spaces.scope

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.identity.TenantProfileStore
import com.veyq.app.shared.identity.VeyqUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A scope the caller holds, with their own grant ciphertext — the row behind a `ScopeSummary`. `encryptedName`
 *  is the sealed group name for a GROUP scope (ciphertext), null for TENANT/PERSONAL (ADR 0014). `role` is the
 *  caller's own role in the scope (OWNER/MEMBER, cr-0087). */
data class ScopeRow(
    val id: String,
    val kind: String,
    val tenantId: String,
    val role: String,
    val currentEpoch: Int,
    val grantedEpoch: Int,
    val wrappedGrant: String,
    val encryptedName: String?,
)

/** A tenant the caller belongs to — the row behind a `MyTenant` (the space switcher). */
data class TenantRow(val tenantId: String, val name: String, val domain: String, val role: String)

/** A member of a scope — the row behind a `ScopeMember` (the member directory, cr-0077). `hasGrant=false` is a
 *  pending member awaiting delivery. `identityPublicKey` is a key-holder's `asymWrap` target — never a key.
 *  `username` + `sealedName` come from the member's per-tenant profile (ADR 0023); `email` is owner-only — the
 *  handler nulls it for non-owner callers. */
data class ScopeMemberRow(
    val userId: String,
    val username: String,
    val sealedName: String?,
    val email: String?,
    val role: String,
    val identityPublicKey: String,
    val hasGrant: Boolean,
)

/**
 * Persistence for a member's scopes (cr-0026). Operator-blind: stores only the opaque scope row + the
 * ciphertext grant on the membership; never a scope key. The active tenant is resolved by domain (Host).
 */
@ApplicationScoped
class ScopesStore(private val profiles: TenantProfileStore) {

    /** The tenant id for a Host/domain, or null if no tenant claims it. */
    fun findTenantIdByDomain(domain: String): String? =
        TenantEntity.find("domain", domain).firstResult()?.id

    /** Whether the caller already holds a membership in this tenant (i.e. belongs to it). */
    fun isMember(userId: String, tenantId: String): Boolean =
        MembershipEntity.count("userId = ?1 and tenantId = ?2", userId, tenantId) > 0

    /** Whether the caller already has a PERSONAL scope in this tenant (one per member, per tenant). */
    fun personalScopeExists(userId: String, tenantId: String): Boolean {
        val scopeIds = MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId)
            .list().map { it.scopeId }
        return scopeIds.isNotEmpty() &&
            ScopeEntity.count("id in ?1 and kind = ?2", scopeIds, ScopeEntity.PERSONAL) > 0
    }

    /** Birth a PERSONAL scope for the caller in this tenant + the self-membership (grant = opaque ciphertext). */
    @Transactional
    fun createPersonal(userId: String, tenantId: String, wrappedGrant: String): String {
        val now = Instant.now()
        val scopeId = UUID.randomUUID().toString()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.PERSONAL
            createdAt = now
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            this.wrappedGrant = wrappedGrant
            createdAt = now
        }.persist()
        return scopeId
    }

    /** Birth a GROUP scope for the caller in this tenant + the OWNER self-membership (en.spaces.group-scope,
     *  cr-0032). Mirrors [createPersonal] but stores the opaque `encryptedName` (the group name sealed under
     *  `K_SC`) on the scope row. Group names are not unique — no existence check. */
    @Transactional
    fun createGroup(userId: String, tenantId: String, encryptedName: String, wrappedGrant: String): String {
        val now = Instant.now()
        val scopeId = UUID.randomUUID().toString()
        ScopeEntity().apply {
            id = scopeId
            this.tenantId = tenantId
            kind = ScopeEntity.GROUP
            this.encryptedName = encryptedName
            createdAt = now
        }.persist()
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.OWNER
            this.wrappedGrant = wrappedGrant
            createdAt = now
        }.persist()
        return scopeId
    }

    /** The caller's scopes in this tenant, each with their own grant ciphertext (to open client-side). A
     *  grant-less ("pending") membership is skipped — the member can't open that scope yet, so it never
     *  surfaces as an openable scope (they see the Personal fallback until delivery lands, cr-0076). */
    fun listMine(userId: String, tenantId: String): List<ScopeRow> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().mapNotNull { m ->
            m.wrappedGrant?.let { grant ->
                ScopeEntity.findById(m.scopeId)?.let {
                    ScopeRow(it.id, it.kind, it.tenantId, m.role, it.currentEpoch, m.grantedEpoch, grant, it.encryptedName)
                }
            }
        }

    /** Whether the caller holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and wrappedGrant is not null", userId, scopeId) > 0

    /** The tenant a scope lives in (for the directory's per-tenant profile join + owner-email gating). */
    fun scopeTenantId(scopeId: String): String? = ScopeEntity.findById(scopeId)?.tenantId

    /** The kind of a scope (TENANT/GROUP/PERSONAL), or null if there's no such scope. */
    fun scopeKind(scopeId: String): String? = ScopeEntity.findById(scopeId)?.kind

    /** Whether the caller holds an OWNER membership on this scope (the group-admission gate, ADR 0014 §4). */
    fun isScopeOwner(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and role = ?3", userId, scopeId, MembershipEntity.OWNER) > 0

    /** Whether the user already holds any membership on this scope (grant or not) — the idempotency check. */
    fun isScopeMember(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2", userId, scopeId) > 0

    /** Resolve a per-tenant username to a userId within a tenant (cr-0086) — the tenant-scoped resolve. */
    fun resolveUsernameInTenant(tenantId: String, username: String): String? =
        profiles.findUserIdByUsername(tenantId, username)

    // --- member management + rotation (cr-0088) ---

    /** The number of OWNER memberships on a scope — the last-owner guard (ADR 0007). */
    fun ownerCount(scopeId: String): Long =
        MembershipEntity.count("scopeId = ?1 and role = ?2", scopeId, MembershipEntity.OWNER)

    /** A member's role in a scope, or null if they hold no membership there. */
    fun memberRole(userId: String, scopeId: String): String? =
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.role

    /** Set a member's role (promote/demote). No-op if not a member. */
    @Transactional
    fun setRole(userId: String, scopeId: String, role: String) {
        MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult()?.let {
            it.role = role
            it.persist()
        }
    }

    /** Delete a member's membership (layer-1 removal — the instant access cut). */
    @Transactional
    fun removeMembership(userId: String, scopeId: String) {
        MembershipEntity.delete("userId = ?1 and scopeId = ?2", userId, scopeId)
    }

    /** The scope's current content-key epoch, or null if there's no such scope. */
    fun currentEpoch(scopeId: String): Int? = ScopeEntity.findById(scopeId)?.currentEpoch

    /** The epoch-chain links of a scope (ascending), each `epoch → wrappedPrev` (ADR 0024). */
    fun epochLinks(scopeId: String): List<Pair<Int, String>> =
        ScopeEpochEntity.find("scopeId", scopeId).list().sortedBy { it.epoch }.map { it.epoch to it.wrappedPrev }

    /** Commit a rotation (ADR 0024): bump the current epoch, store the chain-link, apply the re-grants (each to the
     *  new epoch). Client-minted; the server stores only ciphertext. Returns the new epoch. */
    @Transactional
    fun rotate(scopeId: String, wrappedPrev: String, grants: List<Pair<String, String>>): Int {
        val scope = ScopeEntity.findById(scopeId)!!
        val newEpoch = scope.currentEpoch + 1
        scope.currentEpoch = newEpoch
        scope.persist()
        ScopeEpochEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            epoch = newEpoch
            this.wrappedPrev = wrappedPrev
            createdAt = Instant.now()
        }.persist()
        grants.forEach { (uid, g) ->
            MembershipEntity.find("userId = ?1 and scopeId = ?2", uid, scopeId).firstResult()?.let {
                it.wrappedGrant = g
                it.grantedEpoch = newEpoch
                it.persist()
            }
        }
        return newEpoch
    }

    /** Admit a member to a scope **grant-less** (role=MEMBER, no grant) — grant-delivery wraps `K_SC` to them
     *  later (cr-0076). Operator-blind: **no key is written here**. */
    @Transactional
    fun addGrantlessMember(userId: String, tenantId: String, scopeId: String) {
        MembershipEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.tenantId = tenantId
            this.scopeId = scopeId
            role = MembershipEntity.MEMBER
            wrappedGrant = null
            createdAt = Instant.now()
        }.persist()
    }

    /** The directory row for one member of a scope (the add response). `email` is included; the handler nulls it. */
    fun scopeMemberRow(scopeId: String, userId: String): ScopeMemberRow? {
        val tenantId = ScopeEntity.findById(scopeId)?.tenantId ?: return null
        val m = MembershipEntity.find("userId = ?1 and scopeId = ?2", userId, scopeId).firstResult() ?: return null
        val u = VeyqUser.findById(userId) ?: return null
        val p = profiles.byUserInTenant(tenantId)[userId]
        return ScopeMemberRow(userId, p?.username ?: "", p?.sealedName, u.email, m.role, u.identityPublicKey, m.wrappedGrant != null)
    }

    /** Whether the user is the tenant's OWNER (gates the owner-only email in the directory). */
    fun isTenantOwner(userId: String, tenantId: String): Boolean =
        MembershipEntity.count(
            "userId = ?1 and tenantId = ?2 and role = ?3", userId, tenantId, MembershipEntity.OWNER,
        ) > 0

    /** Every member of a scope: identity (per-tenant username + sealed name), pubkey, grant-present, email. The
     *  email is included on every row here; the handler nulls it for non-owner callers (ADR 0023). */
    fun listMembers(scopeId: String): List<ScopeMemberRow> {
        val tenantId = ScopeEntity.findById(scopeId)?.tenantId ?: return emptyList()
        val profileByUser = profiles.byUserInTenant(tenantId)
        return MembershipEntity.find("scopeId", scopeId).list().mapNotNull { m ->
            VeyqUser.findById(m.userId)?.let { u ->
                val p = profileByUser[m.userId]
                ScopeMemberRow(
                    m.userId, p?.username ?: "", p?.sealedName, u.email, m.role,
                    u.identityPublicKey, m.wrappedGrant != null,
                )
            }
        }
    }

    /** Every tenant the caller belongs to (one row per tenant, name+domain joined) — the space switcher. */
    fun myTenants(userId: String): List<TenantRow> =
        MembershipEntity.find("userId", userId).list()
            .groupBy { it.tenantId }
            .mapNotNull { (tenantId, memberships) ->
                TenantEntity.findById(tenantId)?.let { t -> TenantRow(t.id, t.name, t.domain, memberships.first().role) }
            }
            .sortedBy { it.name }
}
