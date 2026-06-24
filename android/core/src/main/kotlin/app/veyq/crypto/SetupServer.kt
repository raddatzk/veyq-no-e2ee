package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * uc.admin.setup-server (Android) — cold E2EE registration of the first Server Owner.
 * All key material is generated here; the server only ever receives ciphertext + public keys.
 */
object SetupServer {
    private val argon = V1.ArgonParams(t = 3, m = 32768, p = 1, dkLen = 32)
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val phcB64 = Base64.getEncoder().withoutPadding()
    private fun b64url(b: ByteArray): String = b64.encodeToString(b)
    private fun blob(bytes: ByteArray): JSONObject = JSONObject().put("suiteId", "v1").put("bytes", b64url(bytes))
    fun authVerifierPhc(salt: ByteArray, verifier: ByteArray): String =
        "\$argon2id\$v=19\$m=${argon.m},t=${argon.t},p=${argon.p}\$${phcB64.encodeToString(salt)}\$${phcB64.encodeToString(verifier)}"

    data class EmergencyKit(val secretKey: String)

    fun buildRequestJson(email: String, displayName: String, password: String): Pair<String, EmergencyKit> {
        val id = V1.generateIdentity()
        val masterKey = V1.randomBytes(32)
        val wrappedPriv = V1.symWrap(masterKey, V1.serializeIdentityPrivate(id), "identity-priv", V1.randomBytes(12))

        val secretKey = V1.randomBytes(32)
        val salt = V1.randomBytes(16)
        val kek = V1.kekPasswordSecret(secretKey, password, salt, argon)
        val masterWrap = V1.symWrap(kek, masterKey, "master-key", V1.randomBytes(12))

        // RECOVERY wrap: master key under the Secret Key ALONE — released only via the email-token-gated reset
        // (recovery needs Secret Key AND email). Never returned in the login key bag. kdfParams carry params.
        val recSalt = V1.randomBytes(16)
        val recWrap = V1.symWrap(V1.kekRecovery(secretKey, recSalt, argon), masterKey, "master-key", V1.randomBytes(12))

        val authSalt = V1.randomBytes(16)
        val verifier = V1.kekRecovery(
            java.text.Normalizer.normalize(password, java.text.Normalizer.Form.NFC).toByteArray(Charsets.UTF_8),
            authSalt, argon,
        )

        // personal scope is self-owned and unshared; grant it only to this identity.
        val personal = V1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
        val personalPublic = personal.pubKemX + personal.pubKemM
        val personalGrant = V1.asymWrap(
            id.pubKemX, id.pubKemM, V1.serializeIdentityPrivate(personal), "personal-scope-grant", V1.randomBytes(12),
        )

        val masterKeyWraps = JSONArray()
            .put(JSONObject().put("unlockMethod", "PASSWORD").put("suiteId", "v1").put("wrappedKey", b64url(masterWrap)).put("kdfParams", b64url(V1.encodeKdfParams(argon, salt))))
            // RECOVERY wrap: master key under the Secret Key alone — released only via the email-token-gated reset.
            .put(JSONObject().put("unlockMethod", "RECOVERY").put("suiteId", "v1").put("wrappedKey", b64url(recWrap)).put("kdfParams", b64url(V1.encodeKdfParams(argon, recSalt))))
        val json = JSONObject()
            .put("email", email)
            .put("displayName", displayName)
            .put("identityPublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", b64url(id.pubKemX + id.pubKemM)).put("signPublicKey", b64url(id.pubSign)))
            .put("wrappedIdentityPrivateKey", blob(wrappedPriv))
            .put("masterKeyWraps", masterKeyWraps)
            .put("authVerifier", JSONObject().put("kind", "ARGON2_PROOF").put("suiteId", "v1").put("verifier", authVerifierPhc(authSalt, verifier)))
            // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
            .put("recoveryAuthTag", b64url(V1.recoveryAuthTag(masterKey)))
            .put("personalScopePublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", b64url(personalPublic)))
            .put("wrappedPersonalScopePrivateKey", blob(personalGrant))
            .toString()
        return json to EmergencyKit(b64url(secretKey))
    }

    /** Returns (raw response body, emergency kit). Throws on non-200. */
    fun registerServerOwner(apiBase: String, email: String, displayName: String, password: String): Pair<String, EmergencyKit> {
        val (json, kit) = buildRequestJson(email, displayName, password)
        // Unauthenticated (cold setup) → null token.
        return ApiClient.ok(apiBase, null, "/api/setup/owner", "POST", json, label = "setup/owner") to kit
    }
}
