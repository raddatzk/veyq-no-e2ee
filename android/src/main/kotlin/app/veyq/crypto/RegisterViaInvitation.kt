package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.util.Base64

/**
 * uc.account.register-via-invitation (Android) — a brand-new invitee accepts an owner invite. All key
 * material is born here; one POST creates the account, mints the tenant scope as a self grant, and
 * returns a session. The server only ever receives ciphertext + public keys (+ an auth verifier).
 */
object RegisterViaInvitation {
    private val argon = V1.ArgonParams(t = 3, m = 8192, p = 1, dkLen = 32)
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val client = HttpClient.newHttpClient()
    private fun b64url(b: ByteArray): String = enc.encodeToString(b)

    data class RegisterResult(
        val sessionToken: String,
        val user: JSONObject,
        val tenant: JSONObject,
        val emergencyKit: SetupServer.EmergencyKit,
    )

    /** Public invite metadata — the bootstrap secret (from the URL fragment) is never sent here. */
    fun getInviteMetadata(apiBase: String, inviteId: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenant-invites/$inviteId")).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("invite metadata failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** Builds the tenant scope grant (scopePublicKey, wrappedScopePrivateKey — both base64url). */
    fun interface TenantScopeProvider {
        fun grant(idPubKemX: ByteArray, idPubKemM: ByteArray): Pair<String, String>
    }

    fun buildRequestJson(
        bootstrapSecret: String, email: String, displayName: String, password: String, tenantScope: TenantScopeProvider,
    ): Pair<String, SetupServer.EmergencyKit> {
        val id = V1.generateIdentity()
        val masterKey = V1.randomBytes(32)
        val wrappedPriv = V1.symWrap(masterKey, V1.serializeIdentityPrivate(id), "identity-priv", V1.randomBytes(12))

        val secretKey = V1.randomBytes(32)
        val salt = V1.randomBytes(16)
        val kek = V1.kekPasswordSecret(secretKey, password, salt, argon)
        val masterWrap = V1.symWrap(kek, masterKey, "master-key", V1.randomBytes(12))

        val authSalt = V1.randomBytes(16)
        val verifier = V1.kekRecovery(
            Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), authSalt, argon,
        )

        // create the unshared personal scope; only this user receives a grant
        val personal = V1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
        val personalPublic = personal.pubKemX + personal.pubKemM
        val personalGrant = V1.asymWrap(
            id.pubKemX, id.pubKemM, V1.serializeIdentityPrivate(personal), "personal-scope-grant", V1.randomBytes(12),
        )

        // the tenant scope grant — minted (owner) or temp-unwrapped + re-wrapped (member)
        val (scopePublic, grant) = tenantScope.grant(id.pubKemX, id.pubKemM)

        // base64url and the simple ASCII inputs need no JSON escaping in this flow.
        val json = """{"bootstrapSecret":"$bootstrapSecret","email":"$email","displayName":"$displayName",""" +
            """"identityPublicKey":{"suiteId":"v1","kemPublicKey":"${b64url(id.pubKemX + id.pubKemM)}","signPublicKey":"${b64url(id.pubSign)}"},""" +
            """"wrappedIdentityPrivateKey":{"suiteId":"v1","bytes":"${b64url(wrappedPriv)}"},""" +
            """"masterKeyWraps":[{"unlockMethod":"PASSWORD","suiteId":"v1","wrappedKey":"${b64url(masterWrap)}","kdfParams":"${b64url(salt)}"}],""" +
            """"authVerifier":{"kind":"ARGON2_PROOF","suiteId":"v1","verifier":"${b64url(authSalt + verifier)}"},""" +
            """"personalScopePublicKey":{"suiteId":"v1","kemPublicKey":"${b64url(personalPublic)}"},""" +
            """"wrappedPersonalScopePrivateKey":{"suiteId":"v1","bytes":"${b64url(personalGrant)}"},""" +
            """"scopePublicKey":{"suiteId":"v1","kemPublicKey":"$scopePublic"},""" +
            """"wrappedScopePrivateKey":{"suiteId":"v1","bytes":"$grant"}}"""
        return json to SetupServer.EmergencyKit(b64url(secretKey))
    }

    /** Register from an owner invite — the empty tenant's scope keypair is minted here (self grant). */
    fun registerViaInvitation(
        apiBase: String, inviteId: String, bootstrapSecret: String, email: String, displayName: String, password: String,
    ): RegisterResult = registerWithScope(apiBase, inviteId, bootstrapSecret, email, displayName, password) { idX, idM ->
        val scope = V1.generateIdentity()
        b64url(scope.pubKemX + scope.pubKemM) to
            b64url(V1.asymWrap(idX, idM, V1.serializeIdentityPrivate(scope), "scope-grant", V1.randomBytes(12)))
    }

    /** Core registration assembly shared by the owner-claim and temp-wrap member flows. */
    fun registerWithScope(
        apiBase: String, inviteId: String, bootstrapSecret: String, email: String, displayName: String, password: String,
        tenantScope: TenantScopeProvider,
    ): RegisterResult {
        val (json, kit) = buildRequestJson(bootstrapSecret, email, displayName, password, tenantScope)
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/tenant-invites/$inviteId/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("register failed: ${resp.statusCode()} ${resp.body()}")
        val body = JSONObject(resp.body())
        return RegisterResult(
            sessionToken = body.getString("sessionToken"),
            user = body.getJSONObject("user"),
            tenant = body.getJSONObject("tenant"),
            emergencyKit = kit,
        )
    }
}
