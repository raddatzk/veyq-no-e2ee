package com.veyq.app.features.keys.removeunlockwrap

import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * uc.keys.remove-unlock-method — delete one of the caller's opaque Master Key unlock wraps.
 * Two lockout guards: the account must always retain at least one PASSWORD wrap (the mandatory,
 * hardware-independent Master-Key-capable method — the blind server can't verify passkey PRF
 * capability), and never drop below one stored wrap at all.
 */
@ApplicationScoped
class RemoveUnlockWrapHandler(
    private val keyBlobs: KeyBlobRepository,
    private val sessions: SessionService,
) {
    @Transactional
    fun removeMyMasterKeyWrap(auth: String?, unlockMethod: String, credentialRef: String?) {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val method = parseMethod(unlockMethod)

        // Take a row-locked snapshot of all the user's wraps and decide from it. Two concurrent removals
        // would otherwise both read the pre-delete count, each pass the floor check, and drop below it
        // (TOCTOU) — the lock serializes them so the lockout guards hold.
        val blobs = keyBlobs.listByUserIdForUpdate(userId)
        val blob = blobs.firstOrNull { it.unlockMethod == method.value && it.credentialRef == credentialRef }
            ?: throw ApiException(404, "NOT_FOUND", "Master key wrap not found")

        // Removing the last PASSWORD wrap would strip the mandatory recovery path (Emergency Kit),
        // leaving only hardware-bound methods that can all be lost → permanent lockout.
        if (method == MasterKeyWrap.UnlockMethod.PASSWORD &&
            blobs.count { it.unlockMethod == MasterKeyWrap.UnlockMethod.PASSWORD.value } <= 1
        ) {
            throw ApiException(409, "LAST_CAPABLE_METHOD", "Cannot remove the last password unlock method")
        }
        if (blobs.size <= 1) {
            throw ApiException(409, "LAST_UNLOCK_METHOD", "Cannot remove the last unlock method")
        }

        keyBlobs.delete(blob)
    }

    private fun parseMethod(unlockMethod: String): MasterKeyWrap.UnlockMethod =
        try {
            MasterKeyWrap.UnlockMethod.valueOf(unlockMethod)
        } catch (_: IllegalArgumentException) {
            throw ApiException(400, "UNKNOWN_UNLOCK_METHOD", "Unknown unlock method: $unlockMethod")
        }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
}
