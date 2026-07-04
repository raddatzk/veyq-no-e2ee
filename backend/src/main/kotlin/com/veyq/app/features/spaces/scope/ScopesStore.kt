package com.veyq.app.features.spaces.scope

import com.veyq.app.features.admin.tenant.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.shared.identity.VeyqUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A scope the caller holds, with their own grant ciphertext — the row behind a `ScopeSummary`. */
data class ScopeRow(val id: String, val kind: String, val tenantId: String, val wrappedGrant: String)

/** A tenant the caller belongs to — the row behind a `MyTenant` (the space switcher). */
data class TenantRow(val tenantId: String, val name: String, val domain: String, val role: String)

/** A member of a scope — the row behind a `ScopeMember` (the member directory, cr-0077). `hasGrant=false` is a
 *  pending member awaiting delivery. `identityPublicKey` is a key-holder's `asymWrap` target — never a key. */
data class ScopeMemberRow(
    val userId: String,
    val displayName: String,
    val email: String,
    val role: String,
    val identityPublicKey: String,
    val hasGrant: Boolean,
)

/**
 * Persistence for a member's scopes (cr-0026). Operator-blind: stores only the opaque scope row + the
 * ciphertext grant on the membership; never a scope key. The active tenant is resolved by domain (Host).
 */
@ApplicationScoped
class ScopesStore {

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

    /** The caller's scopes in this tenant, each with their own grant ciphertext (to open client-side). A
     *  grant-less ("pending") membership is skipped — the member can't open that scope yet, so it never
     *  surfaces as an openable scope (they see the Personal fallback until delivery lands, cr-0076). */
    fun listMine(userId: String, tenantId: String): List<ScopeRow> =
        MembershipEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).list().mapNotNull { m ->
            m.wrappedGrant?.let { grant ->
                ScopeEntity.findById(m.scopeId)?.let { ScopeRow(it.id, it.kind, it.tenantId, grant) }
            }
        }

    /** Whether the caller holds a grant in this scope (is a key-holder there) — the member-directory gate. */
    fun holdsGrant(userId: String, scopeId: String): Boolean =
        MembershipEntity.count("userId = ?1 and scopeId = ?2 and wrappedGrant is not null", userId, scopeId) > 0

    /** Every member of a scope with their identity pubkey + grant-present flag (the directory, owner UI). */
    fun listMembers(scopeId: String): List<ScopeMemberRow> =
        MembershipEntity.find("scopeId", scopeId).list().mapNotNull { m ->
            VeyqUser.findById(m.userId)?.let { u ->
                ScopeMemberRow(m.userId, u.displayName, u.email, m.role, u.identityPublicKey, m.wrappedGrant != null)
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
