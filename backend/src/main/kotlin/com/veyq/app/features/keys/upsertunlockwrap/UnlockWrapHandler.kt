package com.veyq.app.features.keys.upsertunlockwrap

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
        val method = try {
            MasterKeyWrap.UnlockMethod.valueOf(unlockMethod)
        } catch (_: IllegalArgumentException) {
            throw ApiException(400, "UNKNOWN_UNLOCK_METHOD", "Unknown unlock method: $unlockMethod")
        }
        if (wrap.unlockMethod != method) {
            throw ApiException(400, "UNLOCK_METHOD_MISMATCH", "Path unlockMethod must match the payload")
        }
        requireSuite(wrap.suiteId)

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

        return toWrap(blob)
    }

    private fun toWrap(blob: KeyBlob): MasterKeyWrap =
        MasterKeyWrap(
            unlockMethod = MasterKeyWrap.UnlockMethod.valueOf(blob.unlockMethod),
            suiteId = blob.suiteId,
            wrappedKey = enc.encodeToString(blob.wrappedKey),
            kdfParams = blob.kdfParams?.let { enc.encodeToString(it) },
            credentialRef = blob.credentialRef,
        )

    private fun requireSuite(suiteId: String) {
        if (!suites.isKnown(suiteId)) throw ApiException(400, "UNKNOWN_SUITE", "Unknown crypto suite: $suiteId")
    }

    private fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Missing or invalid session")
}
