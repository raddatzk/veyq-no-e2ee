package app.veyq.crypto

import org.json.JSONObject
import java.text.Normalizer
import java.util.Base64

/**
 * uc.spaces / content (Android) — the first real use of a scope: write and read encrypted content under
 * it. AD-03: a stable per-scope content key (wrapped to the scope public key) seals items; the server
 * only ever stores ciphertext. Targets the personal scope here, but the ops are scope-generic.
 */
object ScopeContent {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class OpenScope(val scopeId: String, val scopePkX: ByteArray, val scopePkM: ByteArray, val scopePriv: V1.IdentityPrivate)

    private fun req(apiBase: String, path: String, token: String, method: String = "GET", body: String? = null): Pair<Int, String> =
        ApiClient.send(apiBase, token, path, method, body).let { it.statusCode() to it.body() }

    /** Fetch + open the caller's personal scope: unwrap its private key with the user's identity. */
    fun openPersonalScope(apiBase: String, session: Login.UnlockedSession): OpenScope {
        val (code, body) = req(apiBase, "/api/me/personal-scope", session.sessionToken)
        if (code != 200) throw RuntimeException("personal-scope failed: $code")
        val g = JSONObject(body)
        val idKem = session.kemPublicKey
        val scopePrivBytes = V1.asymUnwrap(
            session.identity.kemX, session.identity.mlkemSeed, idKem.copyOfRange(0, 32), idKem.copyOfRange(32, idKem.size),
            dec.decode(g.getJSONObject("wrappedScopePrivateKey").getString("bytes")), "personal-scope-grant",
        )
        val scopeKem = dec.decode(g.getJSONObject("scopePublicKey").getString("kemPublicKey"))
        return OpenScope(g.getString("scopeId"), scopeKem.copyOfRange(0, 32), scopeKem.copyOfRange(32, scopeKem.size), V1.deserializeIdentityPrivate(scopePrivBytes))
    }

    /** Fetch + open a tenant scope the caller holds a grant for (re-fetches the current grant each call). */
    fun openTenantScope(apiBase: String, session: Login.UnlockedSession, tenantId: String): OpenScope {
        val g = Tenant.getMyScopeGrant(apiBase, session.sessionToken, tenantId)
        val scopePriv = Tenant.openScopeGrant(g, session.identity, session.kemPublicKey)
        val scopeKem = dec.decode(g.getJSONObject("scopePublicKey").getString("kemPublicKey"))
        return OpenScope(g.getString("scopeId"), scopeKem.copyOfRange(0, 32), scopeKem.copyOfRange(32, scopeKem.size), scopePriv)
    }

    /** Open ANY scope the caller holds a grant for, by scope id (e.g. a group scope — no tenant path). */
    fun openScopeById(apiBase: String, session: Login.UnlockedSession, scopeId: String): OpenScope {
        val (code, body) = req(apiBase, "/api/scopes/$scopeId/grants/me", session.sessionToken)
        if (code != 200) throw RuntimeException("scope grant failed: $code")
        val g = JSONObject(body)
        val scopePriv = Tenant.openScopeGrant(g, session.identity, session.kemPublicKey)
        val scopeKem = dec.decode(g.getJSONObject("scopePublicKey").getString("kemPublicKey"))
        return OpenScope(g.getString("scopeId"), scopeKem.copyOfRange(0, 32), scopeKem.copyOfRange(32, scopeKem.size), scopePriv)
    }

    /** Get the scope's content key, provisioning one (wrapped to the scope) on first use. */
    fun getOrCreateContentKey(apiBase: String, session: Login.UnlockedSession, scope: OpenScope): ByteArray {
        val path = "/api/scopes/${scope.scopeId}/content-key"
        val (code, body) = req(apiBase, path, session.sessionToken)
        if (code == 200) {
            val w = JSONObject(body).getJSONObject("wrappedContentKey").getString("bytes")
            return V1.asymUnwrap(scope.scopePriv.kemX, scope.scopePriv.mlkemSeed, scope.scopePkX, scope.scopePkM, dec.decode(w), "content-key")
        }
        if (code != 404) throw RuntimeException("content-key fetch failed: $code")
        val ck = V1.randomBytes(32)
        val wrapped = V1.asymWrap(scope.scopePkX, scope.scopePkM, ck, "content-key", V1.randomBytes(12))
        val json = JSONObject().put("wrappedContentKey", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(wrapped))).toString()
        val (pCode, _) = req(apiBase, path, session.sessionToken, "PUT", json)
        if (pCode == 409) return getOrCreateContentKey(apiBase, session, scope)
        if (pCode != 200) throw RuntimeException("content-key provision failed: $pCode")
        return ck
    }

    /** Seal a string under the content key and store it; returns the item id. */
    fun writeItem(apiBase: String, session: Login.UnlockedSession, scope: OpenScope, contentKey: ByteArray, plaintext: String): String {
        val nonce = V1.randomBytes(12)
        // NFC-normalize so the same logical string seals identically across platforms (web normalizes too)
        val normalized = Normalizer.normalize(plaintext, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, normalized, EMPTY)
        val json = JSONObject().put("sealedContent", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))).toString()
        val (code, body) = req(apiBase, "/api/scopes/${scope.scopeId}/items", session.sessionToken, "POST", json)
        if (code != 200) throw RuntimeException("item create failed: $code")
        return JSONObject(body).getString("id")
    }

    /** List the scope's items and decrypt each back to its plaintext string. */
    fun readItems(apiBase: String, session: Login.UnlockedSession, scope: OpenScope, contentKey: ByteArray): List<String> {
        val (code, body) = req(apiBase, "/api/scopes/${scope.scopeId}/items", session.sessionToken)
        if (code != 200) throw RuntimeException("item list failed: $code")
        val arr = JSONObject(body).getJSONArray("items")
        return (0 until arr.length()).map { i ->
            val sealed = dec.decode(arr.getJSONObject(i).getJSONObject("sealedContent").getString("bytes"))
            String(V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY), Charsets.UTF_8)
        }
    }
}
