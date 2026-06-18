package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Base64

/**
 * uc.spaces.create-group (Android) — a Group is a collaborative sub-space within a tenant (Tenant > Group >
 * Personal). Create one by minting a GROUP_ROOT scope, self-granting it, provisioning a content key, and
 * sealing the NAME under that content key (operator-blind). Members are added by re-wrapping the group
 * scope key to their identity. The server stays blind to the name and the content.
 */
object Groups {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    private fun send(apiBase: String, path: String, token: String, method: String = "GET", body: String? = null): Pair<Int, String> =
        ApiClient.send(apiBase, token, path, method, body).let { it.statusCode() to it.body() }

    /** Decrypt a group's sealed name with its content key. */
    fun openSealedName(contentKey: ByteArray, sealedB64: String): String {
        val sealed = dec.decode(sealedB64)
        val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
        return String(pt, Charsets.UTF_8)
    }

    /** Create a group within a tenant: mint the scope, self-grant, provision the content key, seal the name. */
    fun createGroup(apiBase: String, session: Login.UnlockedSession, tenantId: String, name: String): JSONObject {
        val idKem = session.kemPublicKey
        val scope = V1.generateIdentity()
        val scopePublic = scope.pubKemX + scope.pubKemM
        val grant = V1.asymWrap(idKem.copyOfRange(0, 32), idKem.copyOfRange(32, idKem.size), V1.serializeIdentityPrivate(scope), "scope-grant", V1.randomBytes(12))
        val ck = V1.randomBytes(32)
        val wrappedCk = V1.asymWrap(scope.pubKemX, scope.pubKemM, ck, "content-key", V1.randomBytes(12))
        val nn = V1.randomBytes(12)
        val sealedName = nn + V1.aeadSeal(ck, nn, Normalizer.normalize(name.trim(), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), EMPTY)

        val json = JSONObject()
            .put("scopePublicKey", JSONObject().put("suiteId", "v1").put("kemPublicKey", enc.encodeToString(scopePublic)))
            .put("wrappedScopePrivateKey", blob(grant))
            .put("contentKey", blob(wrappedCk))
            .put("sealedName", blob(sealedName))
            .toString()
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/groups", session.sessionToken, "POST", json)
        if (code != 200) throw RuntimeException("create group failed: $code")
        return JSONObject(body)
    }

    /** The recurring `{"suiteId":"v1","bytes":<base64url>}` sealed-blob wire shape. */
    private fun blob(bytes: ByteArray): JSONObject = JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(bytes))

    fun listGroupMembers(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String): JSONArray {
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/groups/$scopeId/members", session.sessionToken)
        if (code != 200) throw RuntimeException("list group members failed: $code")
        return JSONObject(body).getJSONArray("members")
    }

    /** Add a tenant member to a group (GroupOwner only). Unwraps the caller's OWN group grant to the raw
     *  scope private bytes, then re-wraps them to the target's identity — the server never sees the key. */
    fun addGroupMember(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String, userId: String, targetKemPublicKeyB64: String, role: String = "GroupMember"): JSONObject {
        val (gCode, gBody) = send(apiBase, "/api/scopes/$scopeId/grants/me", session.sessionToken)
        if (gCode != 200) throw RuntimeException("group grant failed: $gCode")
        val g = JSONObject(gBody)
        val myKem = session.kemPublicKey
        val scopePrivBytes = V1.asymUnwrap(session.identity.kemX, session.identity.mlkemSeed, myKem.copyOfRange(0, 32), myKem.copyOfRange(32, myKem.size), dec.decode(g.getJSONObject("wrappedScopePrivateKey").getString("bytes")), "scope-grant")
        val tKem = dec.decode(targetKemPublicKeyB64)
        val wrapped = V1.asymWrap(tKem.copyOfRange(0, 32), tKem.copyOfRange(32, tKem.size), scopePrivBytes, "scope-grant", V1.randomBytes(12))

        val json = JSONObject().put("userId", userId).put("wrappedScopePrivateKey", blob(wrapped)).put("role", role).toString()
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/groups/$scopeId/members", session.sessionToken, "POST", json)
        if (code != 200) throw RuntimeException("add group member failed: $code")
        return JSONObject(body)
    }

    fun setGroupMemberRole(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String, userId: String, role: String): JSONObject {
        val json = JSONObject().put("role", role).toString()
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/groups/$scopeId/members/$userId", session.sessionToken, "PATCH", json)
        if (code != 200) throw RuntimeException("set group role failed: $code")
        return JSONObject(body)
    }

    fun removeGroupMember(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String, userId: String) {
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/groups/$scopeId/members/$userId", session.sessionToken, "DELETE")
        if (code != 204) throw RuntimeException("remove group member failed: $code")
    }

    fun deleteGroup(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String) {
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/groups/$scopeId", session.sessionToken, "DELETE")
        if (code != 204) throw RuntimeException("delete group failed: $code")
    }

    fun deleteNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) {
        val (code, body) = send(apiBase, "/api/scopes/$scopeId/nodes/$nodeId", session.sessionToken, "DELETE")
        if (code != 204) throw RuntimeException("delete node failed: $code")
    }

    fun deleteMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, mediaId: String) {
        val (code, body) = send(apiBase, "/api/scopes/$scopeId/nodes/$nodeId/media/$mediaId", session.sessionToken, "DELETE")
        if (code != 204) throw RuntimeException("delete media failed: $code")
    }

    /** Schedule a tenant for deletion after a grace period (owner only). It stays usable until the deadline,
     *  then the server GC purges it. Idempotent. */
    fun deleteTenant(apiBase: String, session: Login.UnlockedSession, tenantId: String) {
        val (code, body) = send(apiBase, "/api/tenants/$tenantId", session.sessionToken, "DELETE")
        if (code != 204) throw RuntimeException("delete tenant failed: $code")
    }

    /** Cancel a scheduled tenant deletion — clears the deadline (owner only). */
    fun cancelTenantDeletion(apiBase: String, session: Login.UnlockedSession, tenantId: String) {
        val (code, body) = send(apiBase, "/api/tenants/$tenantId/cancel-deletion", session.sessionToken, "POST")
        if (code != 204) throw RuntimeException("cancel tenant deletion failed: $code")
    }
}
