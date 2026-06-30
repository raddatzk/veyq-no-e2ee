package com.veyq.app.features.account.keymaterial

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Outcome of adding a wrap, so the resource can map each case to its status without leaking detail. */
sealed interface AddWrapOutcome {
    data class Created(val id: String) : AddWrapOutcome
    object LimitReached : AddWrapOutcome
}

/**
 * The authenticated account key-material + wrap-management use-cases (uc.account.unlock / trust-device,
 * server side). Authentication is central (`@Authenticated` on the resources → `SecurityIdentity`, cr-0012), so
 * the handler is handed an already-resolved `userId` and only delegates persistence to [KeyMaterialStore] — the
 * resources stay thin HTTP adapters (ADR 0001): Resource → Handler → Store. Each method is @Transactional.
 */
@ApplicationScoped
class KeyMaterialHandler(private val store: KeyMaterialStore) {

    /** The caller's key material for the **normal unlock** path, or null only if the account has none — a
     *  server-side inconsistency for an authenticated user (every account is created with its wraps), surfaced
     *  as a 500 at the boundary.
     *
     *  Serves only the **interactive** unlock wraps. The RECOVERY wrap is a password-less, Secret-Key-only path
     *  (`KEK = Argon2id(secretKey)`) — it must be released **only** by the future email-gated recovery flow,
     *  never in the normal material; otherwise a live session + the Secret Key would open the vault **without
     *  the password**, silently demoting the password from a content factor (cr-0052). PASSWORD / DEVICE /
     *  PASSKEY stay (their KEKs are device- or password-bound); the client's normal unlock uses PASSWORD. */
    @Transactional
    fun forUser(userId: String): KeyMaterial? =
        store.find(userId)?.let { m -> m.copy(wraps = m.wraps.filterNot { it.method == RECOVERY_METHOD }) }

    /** Add a wrap for the authenticated caller. Caps DEVICE enrolments per account so a buggy/malicious
     *  client can't flood the row set (re-trust replaces, so normal use never approaches the cap). */
    @Transactional
    fun addWrap(userId: String, method: String, wrappedKey: String, kdfParams: String?): AddWrapOutcome {
        if (store.countWraps(userId, method) >= MAX_WRAPS_PER_METHOD) return AddWrapOutcome.LimitReached
        return AddWrapOutcome.Created(store.addWrap(userId, method, wrappedKey, kdfParams))
    }

    /** Remove a wrap for the authenticated caller (idempotent) — a no-op for an unknown/other/non-DEVICE wrap. */
    @Transactional
    fun deleteWrap(userId: String, id: String) {
        store.deleteWrap(userId, id)
    }

    companion object {
        const val MAX_WRAPS_PER_METHOD = 20L
        private const val RECOVERY_METHOD = "RECOVERY"
    }
}
