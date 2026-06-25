package com.veyq.app.features.account.keymaterial

import com.veyq.app.features.account.login.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Adds / removes the caller's Master Key wraps (uc.account.trust-device, server side). Resolves the caller
 * from the bearer access token and delegates persistence to [KeyMaterialStore], so [AccountWrapsResource]
 * stays a thin HTTP adapter (ADR 0001). Transactional so the token resolve and the store write share one
 * transaction.
 */
@ApplicationScoped
class WrapsHandler(
    private val sessions: SessionService,
    private val store: KeyMaterialStore,
) {

    /** Add a wrap for the authenticated caller; the new wrap's id, or null if the token is invalid (→ 401). */
    @Transactional
    fun add(accessToken: String?, method: String, wrappedKey: String, kdfParams: String?): String? {
        val userId = accessToken?.let { sessions.resolveUserId(it) } ?: return null
        return store.addWrap(userId, method, wrappedKey, kdfParams)
    }

    /** Remove a wrap for the authenticated caller (idempotent). Returns false if the token is invalid
     *  (→ 401), true once authenticated — the delete itself is a no-op for an unknown/other/floor wrap. */
    @Transactional
    fun delete(accessToken: String?, id: String): Boolean {
        val userId = accessToken?.let { sessions.resolveUserId(it) } ?: return false
        store.deleteWrap(userId, id)
        return true
    }
}
