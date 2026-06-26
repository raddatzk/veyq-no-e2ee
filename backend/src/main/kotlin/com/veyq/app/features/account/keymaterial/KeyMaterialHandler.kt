package com.veyq.app.features.account.keymaterial

import com.veyq.app.features.account.login.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Outcome of adding a wrap, so the resource can map each case to its status without leaking detail. */
sealed interface AddWrapOutcome {
    data class Created(val id: String) : AddWrapOutcome
    object Unauthenticated : AddWrapOutcome
    object LimitReached : AddWrapOutcome
}

/**
 * The authenticated account key-material + wrap-management use-cases (uc.account.unlock / trust-device,
 * server side). Resolves the caller from their bearer access token and delegates persistence to
 * [KeyMaterialStore], so the resources stay thin HTTP adapters (ADR 0001): Resource → Handler → Store. Each
 * method is @Transactional, so the token resolve and the store op share **one** transaction.
 */
@ApplicationScoped
class KeyMaterialHandler(
    private val sessions: SessionService,
    private val store: KeyMaterialStore,
) {

    /** The caller's key material, or null if the access token is missing/invalid/expired/revoked or the user
     *  is gone (→ generic 401 at the boundary). */
    @Transactional
    fun forAccessToken(accessToken: String?): KeyMaterial? {
        val userId = accessToken?.let { sessions.resolveUserId(it) } ?: return null
        return store.find(userId)
    }

    /** Add a wrap for the authenticated caller. Caps DEVICE enrolments per account so a buggy/malicious
     *  client can't flood the row set (re-trust replaces, so normal use never approaches the cap). */
    @Transactional
    fun addWrap(accessToken: String?, method: String, wrappedKey: String, kdfParams: String?): AddWrapOutcome {
        val userId = accessToken?.let { sessions.resolveUserId(it) } ?: return AddWrapOutcome.Unauthenticated
        if (store.countWraps(userId, method) >= MAX_WRAPS_PER_METHOD) return AddWrapOutcome.LimitReached
        return AddWrapOutcome.Created(store.addWrap(userId, method, wrappedKey, kdfParams))
    }

    /** Remove a wrap for the authenticated caller (idempotent). Returns false if the token is invalid
     *  (→ 401), true once authenticated — the delete itself is a no-op for an unknown/other/non-DEVICE wrap. */
    @Transactional
    fun deleteWrap(accessToken: String?, id: String): Boolean {
        val userId = accessToken?.let { sessions.resolveUserId(it) } ?: return false
        store.deleteWrap(userId, id)
        return true
    }

    companion object {
        const val MAX_WRAPS_PER_METHOD = 20L
    }
}
