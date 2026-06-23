package com.veyq.app.features.spaces.switchactivespace

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MySpaceList
import com.veyq.app.generated.model.SpaceEntry
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.GroupMembershipRepository
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.identity.UserRepository
import com.veyq.app.shared.storage.BlobStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import com.veyq.app.platform.error.unauthorized

/**
 * uc.spaces.switch-active-space (roster) — the unified space switcher: tenant contexts the caller can
 * enter, plus the caller's personal space once at least one tenant context exists. A space appears only if
 * its scope key is actually held; switching between entries is pure client context.
 */
@ApplicationScoped
class MySpacesHandler(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
    private val tenants: TenantRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val groupMembers: GroupMembershipRepository,
    private val sessions: SessionService,
    private val store: BlobStore,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val logoUrlTtlSeconds = 900

    @Transactional
    fun listMySpaces(auth: String?): MySpaceList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        val entries = mutableListOf<SpaceEntry>()

        // Batch the roster so the query count is fixed, not O(tenants + groups): one read of every grant the
        // caller holds (access = the wrap, ADR 0005), then the memberships, the relevant scopes and tenants by
        // id-set — assembled in memory. "Holds the scope key" = the scope id is in `held`.
        val held = grants.listByUserId(userId).mapTo(HashSet()) { it.scopeId }
        val activeMemberships = memberships.listActiveByUserId(userId)
        val groupMemberships = groupMembers.listByUserId(userId)
        val tenantRootByTenantId = scopes.listTenantRootsByTenantIds(activeMemberships.map { it.tenantId }.toSet()).associateBy { it.tenantId }
        val groupScopeById = scopes.listByIds(groupMemberships.map { it.scopeId }.toSet()).associateBy { it.id }
        val tenantsById = tenants.listByIds(
            (activeMemberships.map { it.tenantId } + groupScopeById.values.mapNotNull { it.tenantId }).toSet(),
        ).associateBy { it.id }

        // tenant spaces — the tenant-wide space for every tenant the caller is an active member of (+ holds the grant)
        val tenantEntries = mutableListOf<SpaceEntry>()
        activeMemberships.forEach { m ->
            val tenant = tenantsById[m.tenantId] ?: return@forEach
            val scope = tenantRootByTenantId[m.tenantId] ?: return@forEach
            if (scope.id !in held) return@forEach
            tenantEntries += SpaceEntry(
                kind = SpaceEntry.Kind.TENANT,
                tenantId = tenant.id,
                name = tenant.name,
                displayName = tenant.displayName,
                domain = tenant.domain,
                logoUrl = tenant.logoUrl(),
                role = m.role.name,
                scheduledDeletionAt = tenant.scheduledDeletionAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
                scopeId = scope.id,
                scopePublicKey = scope.toPublicKey(),
            )
        }

        // group spaces — every GROUP_ROOT scope the caller is a member of and holds the grant for. The name
        // is sealed under the group content key, so it ships as ciphertext (the client decrypts it).
        val groupEntries = mutableListOf<SpaceEntry>()
        groupMemberships.forEach { gm ->
            val scope = groupScopeById[gm.scopeId] ?: return@forEach
            if (scope.id !in held) return@forEach
            groupEntries += SpaceEntry(
                kind = SpaceEntry.Kind.GROUP,
                tenantId = scope.tenantId,
                // the group's tenant domain — so a cross-host tenant switch can navigate to the right host
                domain = scope.tenantId?.let { tenantsById[it]?.domain },
                logoUrl = scope.tenantId?.let { tenantsById[it]?.logoUrl() },
                role = gm.role,
                sealedName = Blob(suiteId = scope.nameSuiteId ?: scope.suiteId, bytes = enc.encodeToString(scope.sealedName ?: ByteArray(0))),
                scopeId = scope.id,
                scopePublicKey = scope.toPublicKey(),
            )
        }

        // Personal spaces are part of a tenant, not a global workspace. Tenant purge therefore removes them
        // with the rest of that tenant's scopes.
        val visibleTenantIds = (tenantEntries.mapNotNull { it.tenantId } + groupEntries.mapNotNull { it.tenantId }).toSet()
        scopes.listPersonalRootsByOwnerAndTenantIds(userId, visibleTenantIds).forEach { personal ->
            if (personal.id !in held) return@forEach
            val tenant = personal.tenantId?.let { tenantsById[it] }
            entries += SpaceEntry(
                kind = SpaceEntry.Kind.PERSONAL,
                tenantId = personal.tenantId,
                name = tenant?.name,
                displayName = user.displayName,
                domain = tenant?.domain,
                logoUrl = tenant?.logoUrl(),
                scopeId = personal.id,
                scopePublicKey = personal.toPublicKey(),
            )
        }
        entries += tenantEntries
        entries += groupEntries

        return MySpaceList(spaces = entries)
    }

    private fun Scope.toPublicKey() = KemPublicKey(suiteId = suiteId, kemPublicKey = enc.encodeToString(kemPublicKey))

    private fun Tenant.logoUrl(): String? {
        val key = logoObjectKey ?: return null
        return store.presignGet(key, logoUrlTtlSeconds) ?: "/api/tenants/$id/logo?v=${key.substringAfterLast('/')}"
    }

}
