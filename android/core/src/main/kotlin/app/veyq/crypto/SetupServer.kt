package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.admin.setup-server (Android) — cold E2EE registration of the first Server Owner.
 * All key material is generated here; the server only ever receives ciphertext + public keys.
 */
object SetupServer {
    private val argon = V1.ArgonParams(t = 3, m = 65536, p = 1, dkLen = 32)
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun b64url(b: ByteArray): String = b64.encodeToString(b)

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

        // base64url fields are JSON-safe; free text (email, displayName) is escaped via JSONObject.quote.
        val json = """{"email":${JSONObject.quote(email)},"displayName":${JSONObject.quote(displayName)},""" +
            """"identityPublicKey":{"suiteId":"v1","kemPublicKey":"${b64url(id.pubKemX + id.pubKemM)}","signPublicKey":"${b64url(id.pubSign)}"},""" +
            """"wrappedIdentityPrivateKey":{"suiteId":"v1","bytes":"${b64url(wrappedPriv)}"},""" +
            """"masterKeyWraps":[{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"${b64url(masterWrap)}","kdfParams":"${b64url(V1.encodeKdfParams(argon, salt))}"},""" +
            """{"unlockMethod":"RECOVERY","suiteId":"v1","wrappedKey":"${b64url(recWrap)}","kdfParams":"${b64url(V1.encodeKdfParams(argon, recSalt))}"}],""" +
            """"authVerifier":{"kind":"ARGON2_PROOF","suiteId":"v1","verifier":"${b64url(authSalt + verifier)}"},""" +
            // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
            """"recoveryAuthTag":"${b64url(V1.recoveryAuthTag(masterKey))}",""" +
            """"personalScopePublicKey":{"suiteId":"v1","kemPublicKey":"${b64url(personalPublic)}"},""" +
            """"wrappedPersonalScopePrivateKey":{"suiteId":"v1","bytes":"${b64url(personalGrant)}"}}"""
        return json to EmergencyKit(b64url(secretKey))
    }

    /** Returns (raw response body, emergency kit). Throws on non-200. */
    fun registerServerOwner(apiBase: String, email: String, displayName: String, password: String): Pair<String, EmergencyKit> {
        val (json, kit) = buildRequestJson(email, displayName, password)
        val client = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/setup/owner"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("setup/owner failed: ${resp.statusCode()} ${resp.body()}")
        return resp.body() to kit
    }
}
