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
import com.veyq.app.shared.tenancy.MembershipStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import com.veyq.app.platform.error.unauthorized

/**
 * uc.spaces.switch-active-space (roster) — the unified space switcher: tenant-bound personal scopes,
 * TENANT_ROOT scopes and GROUP_ROOT scopes the caller can enter. A space appears only if its scope key is
 * actually held; switching between entries is pure client context.
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
    fun listSpaces(auth: String?, tenantId: String): MySpaceList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        val entries = mutableListOf<SpaceEntry>()

        val tenant = tenants.findById(tenantId) ?: throw ApiException(404, "TENANT_NOT_FOUND", "Tenant not found")
        val membership = memberships.find(tenantId, userId)
        if (membership == null || membership.status != MembershipStatus.ACTIVE) {
            throw ApiException(403, "NOT_A_MEMBER", "Not an active member of this tenant")
        }

        // Batch the roster so the query count is fixed. "Holds the scope key" = the scope id is in `held`.
        val held = grants.listByUserId(userId).mapTo(HashSet()) { it.scopeId }
        val groupMemberships = groupMembers.listByUserId(userId)
        val groupScopeById = scopes.listByIds(groupMemberships.map { it.scopeId }.toSet()).associateBy { it.id }
        val tenantRoot = scopes.findByTenantId(tenantId)

        scopes.listPersonalRootsByOwnerAndTenantIds(userId, setOf(tenantId)).forEach { personal ->
            if (personal.id !in held) return@forEach
            entries += SpaceEntry(
                kind = SpaceEntry.Kind.PERSONAL,
                tenantId = personal.tenantId,
                name = tenant.name,
                displayName = user.displayName,
                domain = tenant.domain,
                logoUrl = tenant.logoUrl(),
                scopeId = personal.id,
                scopePublicKey = personal.toPublicKey(),
            )
        }

        // tenant space — the TENANT_ROOT space for the current host tenant (+ held grant)
        tenantRoot?.takeIf { it.id in held }?.let { scope ->
            entries += SpaceEntry(
                kind = SpaceEntry.Kind.TENANT,
                tenantId = tenant.id,
                name = tenant.name,
                displayName = tenant.displayName,
                domain = tenant.domain,
                logoUrl = tenant.logoUrl(),
                role = membership.role.name,
                scheduledDeletionAt = tenant.scheduledDeletionAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
                scopeId = scope.id,
                scopePublicKey = scope.toPublicKey(),
            )
        }

        // group spaces — every GROUP_ROOT scope the caller is a member of and holds the grant for. The name
        // is sealed under the group content key, so it ships as ciphertext (the client decrypts it).
        groupMemberships.forEach { gm ->
            val scope = groupScopeById[gm.scopeId] ?: return@forEach
            if (scope.tenantId != tenantId) return@forEach
            if (scope.id !in held) return@forEach
            entries += SpaceEntry(
                kind = SpaceEntry.Kind.GROUP,
                tenantId = scope.tenantId,
                groupId = scope.id,
                domain = tenant.domain,
                logoUrl = tenant.logoUrl(),
                role = gm.role,
                sealedName = Blob(suiteId = scope.nameSuiteId ?: scope.suiteId, bytes = enc.encodeToString(scope.sealedName ?: ByteArray(0))),
                scopeId = scope.id,
                scopePublicKey = scope.toPublicKey(),
            )
        }

        return MySpaceList(spaces = entries)
    }

    private fun Scope.toPublicKey() = KemPublicKey(suiteId = suiteId, kemPublicKey = enc.encodeToString(kemPublicKey))

    private fun Tenant.logoUrl(): String? {
        val key = logoObjectKey ?: return null
        return store.presignGet(key, logoUrlTtlSeconds) ?: "/api/tenant/logo?v=${key.substringAfterLast('/')}"
    }

}
