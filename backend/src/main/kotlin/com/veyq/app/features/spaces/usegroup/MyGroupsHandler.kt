package com.veyq.app.features.spaces.usegroup

import com.veyq.app.generated.model.GroupSpace
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MyGroupList
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

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
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun listMyGroups(auth: String?): MyGroupList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val groups = memberships.listActiveByUserId(userId).mapNotNull { m ->
            val tenant = tenants.findById(m.tenantId) ?: return@mapNotNull null
            val scope = scopes.findByTenantId(m.tenantId) ?: return@mapNotNull null
            grants.find(scope.id, userId) ?: return@mapNotNull null // hold the key or it is not a space you can enter
            GroupSpace(
                tenantId = tenant.id,
                name = tenant.name,
                displayName = tenant.displayName,
                role = m.role,
                scopeId = scope.id,
                scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
            )
        }
        return MyGroupList(groups = groups)
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
}
