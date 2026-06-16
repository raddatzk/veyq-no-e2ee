package com.veyq.app.features.keys.rotateidentity

import com.veyq.app.generated.model.Blob
import com.veyq.app.generated.model.KemPublicKey
import com.veyq.app.generated.model.MyGrantList
import com.veyq.app.generated.model.OwnedGrant
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.ScopeGrantRepository
import com.veyq.app.shared.keymgmt.ScopeRepository
import com.veyq.app.shared.security.ScopeAccessGuard
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.Base64
import com.veyq.app.platform.error.unauthorized

/**
 * uc.keys.rotate-identity (grant listing) — every scope grant the caller holds, with the scope kind +
 * public key, so the client can re-wrap them all to a new identity. Public material + ciphertext only.
 *
 * Returns wrapped scope private keys, so a stale grant for a tenant the caller was removed from must not
 * appear: tenant-scope grants are filtered to active membership (personal scopes always pass).
 */
@ApplicationScoped
class MyGrantsHandler(
    private val grants: ScopeGrantRepository,
    private val scopes: ScopeRepository,
    private val sessions: SessionService,
    private val guard: ScopeAccessGuard,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    @Transactional
    fun listMyGrants(auth: String?): MyGrantList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val owned = grants.listByUserId(userId).mapNotNull { grant ->
            val scope = scopes.findById(grant.scopeId) ?: return@mapNotNull null
            if (!guard.isActiveMember(userId, scope.tenantId)) return@mapNotNull null
            OwnedGrant(
                scopeId = scope.id,
                kind = scope.kind,
                scopePublicKey = KemPublicKey(suiteId = scope.suiteId, kemPublicKey = enc.encodeToString(scope.kemPublicKey)),
                wrappedScopePrivateKey = Blob(suiteId = grant.suiteId, bytes = enc.encodeToString(grant.wrappedPrivateKey)),
            )
        }
        return MyGrantList(grants = owned)
    }

}
