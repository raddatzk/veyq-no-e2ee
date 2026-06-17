package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Base64

/**
 * uc.account.register-via-invitation (Android) — a brand-new invitee accepts an owner invite. All key
 * material is born here; one POST creates the account, mints the tenant scope as a self grant, and
 * returns a session. The server only ever receives ciphertext + public keys (+ an auth verifier).
 */
object RegisterViaInvitation {
    private val argon = V1.ArgonParams(t = 3, m = 32768, p = 1, dkLen = 32)
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private fun b64url(b: ByteArray): String = enc.encodeToString(b)
    private fun blob(bytes: ByteArray): JSONObject = JSONObject().put("suiteId", "v1").put("bytes", b64url(bytes))

    data class RegisterResult(
        val sessionToken: String,
        val user: JSONObject,
        val tenant: JSONObject,
        val emergencyKit: SetupServer.EmergencyKit,
    )

    /** Public invite metadata — the bootstrap secret (from the URL fragment) is never sent here. */
    fun getInviteMetadata(apiBase: String, inviteId: String): JSONObject =
        JSONObject(ApiClient.ok(apiBase, null, "/api/tenant-invites/$inviteId", "GET", label = "invite metadata"))

    /** Fetch the temp-wrapped onboarding material by proving the bootstrap secret (not in public metadata). */
    fun getInviteMaterial(apiBase: String, inviteId: String, bootstrapSecret: String): JSONObject {
        val json = JSONObject().put("bootstrapSecret", bootstrapSecret).toString()
        return JSONObject(ApiClient.ok(apiBase, null, "/api/tenant-invites/$inviteId/material", "POST", json, label = "invite material"))
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

        // RECOVERY wrap: master key under the Secret Key ALONE — released only via the email-token-gated reset.
        val recSalt = V1.randomBytes(16)
        val recWrap = V1.symWrap(V1.kekRecovery(secretKey, recSalt, argon), masterKey, "master-key", V1.randomBytes(12))

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

        val masterKeyWraps = JSONArray()
            .put(JSONObject().put("unlockMethod", "PASSWORD").put("suiteId", "v1").put("wrappedKey", b64url(masterWrap)).put("kdfParams", b64url(V1.encodeKdfParams(argon, salt))))
            .put(JSONObject().put("unlockMethod", "RECOVERY").put("suiteId", "v1").put("wrappedKey", b64url(recWrap)).put("kdfParams", b64url(V1.encodeKdfParams(argon, recSalt))))
        val json = JSONObject()
            .put("bootstrapSecret", bootstrapSecret)
            .put("email", email)
            .put("displayName", displayName)
            .put("identityPublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", b64url(id.pubKemX + id.pubKemM)).put("signPublicKey", b64url(id.pubSign)))
            .put("wrappedIdentityPrivateKey", blob(wrappedPriv))
            .put("masterKeyWraps", masterKeyWraps)
            .put("authVerifier", JSONObject().put("kind", "ARGON2_PROOF").put("suiteId", "v1").put("verifier", b64url(authSalt + verifier)))
            // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
            .put("recoveryAuthTag", b64url(V1.recoveryAuthTag(masterKey)))
            .put("personalScopePublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", b64url(personalPublic)))
            .put("wrappedPersonalScopePrivateKey", blob(personalGrant))
            // scopePublic + grant are already base64url strings from the TenantScopeProvider
            .put("scopePublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", scopePublic))
            .put("wrappedScopePrivateKey", JSONObject().put("suiteId", "v1").put("bytes", grant))
            .toString()
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
        val body = JSONObject(ApiClient.ok(apiBase, null, "/api/tenant-invites/$inviteId/register", "POST", json, label = "register"))
        return RegisterResult(
            sessionToken = body.getString("sessionToken"),
            user = body.getJSONObject("user"),
            tenant = body.getJSONObject("tenant"),
            emergencyKit = kit,
        )
    }
}
