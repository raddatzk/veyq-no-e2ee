package com.veyq.app.features.account.keymaterial

import com.veyq.app.features.account.login.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Resolves the caller from their bearer access token and loads their wrapped key material (uc.account.unlock,
 * server side). The use-case logic sits here so [AccountResource] stays a thin HTTP adapter (ADR 0001):
 * Resource → Handler → Store.
 */
@ApplicationScoped
class KeyMaterialHandler(
    private val sessions: SessionService,
    private val store: KeyMaterialStore,
) {

    /**
     * The caller's key material, or null if the access token is missing/invalid/expired/revoked or the user
     * is gone (→ generic 401 at the boundary). Transactional so the token resolve and the material read share
     * **one** transaction (the nested @Transactional reads join it via REQUIRED propagation).
     */
    @Transactional
    fun forAccessToken(accessToken: String?): KeyMaterial? {
        val userId = accessToken?.let { sessions.resolveUserId(it) } ?: return null
        return store.find(userId)
    }
}
