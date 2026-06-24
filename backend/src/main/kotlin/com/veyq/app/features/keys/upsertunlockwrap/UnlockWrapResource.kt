package com.veyq.app.features.keys.upsertunlockwrap

import com.veyq.app.generated.api.MasterKeyWrapsApi
import com.veyq.app.generated.api.ScopeGrantsApi
import com.veyq.app.generated.model.RewrapMyScopeGrantRequest
import com.veyq.app.generated.model.UpsertMasterKeyWrapRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

/** Thin REST entry implementing generated crypto key APIs; delegates to key-management handlers. */
@ApplicationScoped
class UnlockWrapResource(
    private val unlockWraps: UnlockWrapHandler,
    private val removeUnlockWraps: com.veyq.app.features.keys.removeunlockwrap.RemoveUnlockWrapHandler,
) : MasterKeyWrapsApi {
    override fun listMyKeyWraps(authorization: String): Response =
        Response.ok(unlockWraps.listMyKeyWraps(authorization)).build()

    override fun upsertMyMasterKeyWrap(
        authorization: String,
        unlockMethod: String,
        upsertMasterKeyWrapRequest: UpsertMasterKeyWrapRequest,
    ): Response =
        Response.ok(unlockWraps.upsertMyMasterKeyWrap(authorization, unlockMethod, upsertMasterKeyWrapRequest)).build()

    override fun removeMyMasterKeyWrap(authorization: String, unlockMethod: String, credentialRef: String?): Response {
        removeUnlockWraps.removeMyMasterKeyWrap(authorization, unlockMethod, credentialRef)
        return Response.noContent().build()
    }
}

@ApplicationScoped
class ScopeGrantsResource(
    private val scopeGrants: com.veyq.app.features.keys.rewrapscopegrant.ScopeGrantRewrapHandler,
) : ScopeGrantsApi {
    override fun rewrapMyScopeGrant(
        authorization: String,
        scopeId: String,
        rewrapMyScopeGrantRequest: RewrapMyScopeGrantRequest,
    ): Response =
        Response.ok(scopeGrants.rewrapMyScopeGrant(authorization, scopeId, rewrapMyScopeGrantRequest)).build()

    override fun getMyScopeGrant(authorization: String, scopeId: String): Response =
        Response.ok(scopeGrants.getMyScopeGrant(authorization, scopeId)).build()
}
