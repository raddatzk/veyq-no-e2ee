package app.veyq.crypto

import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Password-reset crypto, deterministic (no backend): registration mints a RECOVERY wrap keyed by the Secret
 * Key alone; it opens to the SAME master key as the PASSWORD wrap; re-wrapping under a new password round-trips.
 */
class ResetPasswordTest {
    private val dec = Base64.getUrlDecoder()
    private val argon = V1.ArgonParams(t = 3, m = 8192, p = 1, dkLen = 32)

    private fun wrap(json: JSONObject, method: String): JSONObject {
        val arr = json.getJSONArray("masterKeyWraps")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("unlockMethod") == method }
    }

    @Test
    fun recoveryWrapRoundTrips() {
        val (jsonStr, kit) = SetupServer.buildRequestJson("u@example.com", "U", "old password")
        val json = JSONObject(jsonStr)
        val secretKey = dec.decode(kit.secretKey)

        val pw = wrap(json, "PASSWORD")
        val (pwParams, pwSalt) = V1.decodeKdfParams(dec.decode(pw.getString("kdfParams")))
        val masterViaPassword = V1.symUnwrap(V1.kekPasswordSecret(secretKey, "old password", pwSalt, pwParams), dec.decode(pw.getString("wrappedKey")), "master-key")

        val rec = wrap(json, "RECOVERY")
        val (recParams, recSalt) = V1.decodeKdfParams(dec.decode(rec.getString("kdfParams")))
        val masterViaRecovery = V1.symUnwrap(V1.kekRecovery(secretKey, recSalt, recParams), dec.decode(rec.getString("wrappedKey")), "master-key")

        assertContentEquals(masterViaPassword, masterViaRecovery)

        // re-wrap under a NEW password (the reset step) — round-trips; the old password no longer opens it
        val newSalt = V1.randomBytes(16)
        val newWrap = V1.symWrap(V1.kekPasswordSecret(secretKey, "a brand new password", newSalt, argon), masterViaRecovery, "master-key", V1.randomBytes(12))
        assertContentEquals(masterViaRecovery, V1.symUnwrap(V1.kekPasswordSecret(secretKey, "a brand new password", newSalt, argon), newWrap, "master-key"))
        assertFailsWith<Exception> { V1.symUnwrap(V1.kekPasswordSecret(secretKey, "old password", newSalt, argon), newWrap, "master-key") }
    }
}
