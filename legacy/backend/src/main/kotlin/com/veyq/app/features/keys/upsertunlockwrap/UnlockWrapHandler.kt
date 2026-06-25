package com.veyq.app.features.keys.upsertunlockwrap

import com.veyq.app.generated.model.KeyWrapList
import com.veyq.app.generated.model.KeyWrapSummary
import com.veyq.app.generated.model.MasterKeyWrap
import com.veyq.app.generated.model.UpsertMasterKeyWrapRequest
import com.veyq.app.platform.error.ApiException
import com.veyq.app.shared.crypto.CryptoSuiteRegistry
import com.veyq.app.shared.keymgmt.KeyBlob
import com.veyq.app.shared.keymgmt.KeyBlobRepository
import com.veyq.app.shared.security.SessionService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID
import com.veyq.app.platform.error.unauthorized

/**
 * uc.keys.upsert-unlock-wrap — store one opaque wrapped Master Key blob for the current user.
 * The Master Key and the method-specific KEK remain client-side.
 */
@ApplicationScoped
class UnlockWrapHandler(
    private val keyBlobs: KeyBlobRepository,
    private val sessions: SessionService,
    private val suites: CryptoSuiteRegistry,
) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    @Transactional
    fun upsertMyMasterKeyWrap(auth: String?, unlockMethod: String, req: UpsertMasterKeyWrapRequest): MasterKeyWrap {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val wrap = req.masterKeyWrap
        val method = parseMethod(unlockMethod)
        if (wrap.unlockMethod != method) {
            throw ApiException(400, "UNLOCK_METHOD_MISMATCH", "Path unlockMethod must match the payload")
        }
        suites.require(wrap.suiteId)

        val existing = keyBlobs.findByUserIdAndUnlockMethod(userId, method.value)
            .firstOrNull { it.credentialRef == wrap.credentialRef }
        val blob = existing ?: KeyBlob().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.unlockMethod = method.value
            createdAt = Instant.now()
            keyBlobs.persist(this)
        }
        blob.suiteId = wrap.suiteId
        blob.wrappedKey = dec.decode(wrap.wrappedKey)
        blob.kdfParams = wrap.kdfParams?.let { dec.decode(it) }
        blob.credentialRef = wrap.credentialRef
        // Device/login name — kept for DEVICE wraps so the user can identify them in settings.
        blob.name = wrap.name?.takeIf { it.isNotBlank() }
        // RECOVERY wraps carry the master-key commitment (required so a later reset can prove Secret-Key
        // possession). Other methods never set it.
        if (method.value == "RECOVERY") {
            if (req.recoveryMasterKeyTag.isNullOrBlank()) throw ApiException(400, "MISSING_RECOVERY_AUTH", "A RECOVERY wrap requires recoveryMasterKeyTag")
            blob.recoveryAuthTag = dec.decode(req.recoveryMasterKeyTag)
        }

        return toWrap(blob)
    }

    /**
     * uc.keys (web) — list the caller's unlock wraps as metadata only (no key ciphertext). Drives the
     * "Devices" list in user settings: each DEVICE entry is one enrolled browser/login.
     */
    @Transactional
    fun listMyKeyWraps(auth: String?): KeyWrapList {
        val userId = sessions.resolve(auth) ?: throw unauthorized()
        val wraps = keyBlobs.listByUserId(userId)
            .sortedBy { it.createdAt }
            .map {
                KeyWrapSummary(
                    unlockMethod = KeyWrapSummary.UnlockMethod.valueOf(it.unlockMethod),
                    credentialRef = it.credentialRef,
                    name = it.name,
                    createdAt = it.createdAt.toString(),
                )
            }
        return KeyWrapList(wraps = wraps)
    }

    private fun toWrap(blob: KeyBlob): MasterKeyWrap =
        MasterKeyWrap(
            unlockMethod = MasterKeyWrap.UnlockMethod.valueOf(blob.unlockMethod),
            suiteId = blob.suiteId,
            wrappedKey = enc.encodeToString(blob.wrappedKey),
            kdfParams = blob.kdfParams?.let { enc.encodeToString(it) },
            credentialRef = blob.credentialRef,
            name = blob.name,
        )


    private fun parseMethod(unlockMethod: String): MasterKeyWrap.UnlockMethod =
        try {
            MasterKeyWrap.UnlockMethod.valueOf(unlockMethod)
        } catch (_: IllegalArgumentException) {
            throw ApiException(400, "UNKNOWN_UNLOCK_METHOD", "Unknown unlock method: $unlockMethod")
        }

}
