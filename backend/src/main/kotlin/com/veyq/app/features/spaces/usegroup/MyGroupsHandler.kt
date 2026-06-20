package com.veyq.app.features.spaces.usegroup

import com.veyq.app.generated.model.GroupSpace
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MyGroupList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.Tenant
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.storage.BlobStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64
import com.veyq.app.platform.error.unauthorized

/**
 * uc.spaces.use-group (roster) — the group spaces the caller can actually enter: a tenant they are an
 * ACTIVE member of AND hold the tenant-root scope grant for (access = the wrap, ADR 0005). Returns human
 * context (name + role) plus the scope id + public key so the client can open it and decrypt content.
 * A membership without the grant (e.g. removed but not yet rotated) is filtered out — no key, no entry.
 */
@ApplicationScoped
class MyGroupsHandler(
    private val memberships: MembershipRepository,
    private val tenants: TenantRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
    private val store: BlobStore,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val logoUrlTtlSeconds = 900

    @Transactional
    fun listMyGroups(auth: String?): MyGroupList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        // Fixed query count (like MySpacesHandler) instead of 3 lookups per tenant: active memberships, their
        // tenants + tenant-root scopes (one IN-query each), and the held-grant set — assembled in memory.
        val active = memberships.listActiveByUserId(userId)
        val tenantIds = active.map { it.tenantId }
        val tenantsById = tenants.listByIds(tenantIds).associateBy { it.id }
        val rootByTenantId = scopes.listTenantRootsByTenantIds(tenantIds).associateBy { it.tenantId }
        val held = grants.listByUserId(userId).map { it.scopeId }.toSet()
        val groups = active.mapNotNull { m ->
            val tenant = tenantsById[m.tenantId] ?: return@mapNotNull null
            val scope = rootByTenantId[m.tenantId] ?: return@mapNotNull null
            if (scope.id !in held) return@mapNotNull null // hold the key or it is not a space you can enter
            GroupSpace(
                tenantId = tenant.id,
                name = tenant.name,
                displayName = tenant.displayName,
                logoUrl = tenant.logoUrl(),
                role = m.role.name,
                scopeId = scope.id,
                scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
            )
        }
        return MyGroupList(groups = groups)
    }

    private fun Tenant.logoUrl(): String? {
        val key = logoObjectKey ?: return null
        return store.presignGet(key, logoUrlTtlSeconds) ?: "/api/tenants/$id/logo?v=${key.substringAfterLast('/')}"
    }

}
