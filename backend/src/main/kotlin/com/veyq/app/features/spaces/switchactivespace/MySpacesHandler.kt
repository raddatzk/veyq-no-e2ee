package com.veyq.app.features.spaces.switchactivespace

import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MySpaceList
import com.veyq.app.generated.model.SpaceEntry
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.Scope
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.SessionService
import com.veyq.app.shared.tenancy.MembershipRepository
import com.veyq.app.shared.tenancy.TenantRepository
import com.veyq.app.shared.identity.UserRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64

/**
 * uc.spaces.switch-active-space (roster) — the unified space switcher: the caller's personal space plus
 * every group they are an active member of AND hold the scope key for (access = the wrap, ADR 0005). A
 * space appears only if its scope key is actually held; switching between entries is pure client context.
 */
@ApplicationScoped
class MySpacesHandler(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
    private val tenants: TenantRepository,
    private val scopes: ScopeRepository,
    private val grants: ScopeGrantRepository,
    private val sessions: SessionService,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun listMySpaces(auth: String?): MySpaceList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val user = users.findById(userId) ?: throw unauthorized()
        val entries = mutableListOf<SpaceEntry>()

        // the personal space — present only if the caller still holds its self-grant
        scopes.findPersonalByOwnerUserId(userId)?.let { personal ->
            if (grants.find(personal.id, userId) != null) {
                entries += SpaceEntry(
                    kind = SpaceEntry.Kind.PERSONAL,
                    displayName = user.displayName,
                    scopeId = personal.id,
                    scopePublicKey = personal.toPublicKey(),
                )
            }
        }

        // group spaces — active membership + holds the tenant-root scope grant
        memberships.listActiveByUserId(userId).forEach { m ->
            val tenant = tenants.findById(m.tenantId) ?: return@forEach
            val scope = scopes.findByTenantId(m.tenantId) ?: return@forEach
            grants.find(scope.id, userId) ?: return@forEach
            entries += SpaceEntry(
                kind = SpaceEntry.Kind.GROUP,
                tenantId = tenant.id,
                name = tenant.name,
                displayName = tenant.displayName,
                role = m.role,
                scopeId = scope.id,
                scopePublicKey = scope.toPublicKey(),
            )
        }

        return MySpaceList(spaces = entries)
    }

    private fun Scope.toPublicKey() = KemPublicKey(suiteId = suiteId, kemPublicKey = enc.encodeToString(kemPublicKey))

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
}
