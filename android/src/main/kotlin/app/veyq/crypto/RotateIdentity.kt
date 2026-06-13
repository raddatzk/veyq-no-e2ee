package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.keys.rotate-identity (Android) — replace the account identity keypair and re-wrap every owned grant
 * to it. The master key + unlock wraps are unchanged, so login still works; only the identity moves.
 */
object RotateIdentity {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()

    private fun context(kind: String) = if (kind == "PERSONAL_ROOT") "personal-scope-grant" else "scope-grant"

    fun rotateIdentity(apiBase: String, session: Login.UnlockedSession): JSONObject {
        // 1. new identity, its private key wrapped under the unchanged master key
        val newId = V1.generateIdentity()
        val wrappedNewPriv = V1.symWrap(session.masterKey, V1.serializeIdentityPrivate(newId), "identity-priv", V1.randomBytes(12))

        // 2. list every grant and re-wrap each from the OLD identity to the NEW one (per-scope context)
        val listReq = HttpRequest.newBuilder(URI.create("$apiBase/api/me/grants"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val listResp = client.send(listReq, HttpResponse.BodyHandlers.ofString())
        if (listResp.statusCode() != 200) throw RuntimeException("list grants failed: ${listResp.statusCode()}")
        val arr = JSONObject(listResp.body()).getJSONArray("grants")
        val oldPkX = session.kemPublicKey.copyOfRange(0, 32)
        val oldPkM = session.kemPublicKey.copyOfRange(32, session.kemPublicKey.size)
        val grantParts = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val ctx = context(g.getString("kind"))
            val scopePrivBytes = V1.asymUnwrap(
                session.identity.kemX, session.identity.mlkemSeed, oldPkX, oldPkM,
                dec.decode(g.getJSONObject("wrappedScopePrivateKey").getString("bytes")), ctx,
            )
            val rewrapped = V1.asymWrap(newId.pubKemX, newId.pubKemM, scopePrivBytes, ctx, V1.randomBytes(12))
            grantParts.add("""{"scopeId":"${g.getString("scopeId")}","wrappedScopePrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(rewrapped)}"}}""")
        }

        // 3. swap the identity + grants in one request
        val json = """{"identityPublicKey":{"suiteId":"v1","kemPublicKey":"${enc.encodeToString(newId.pubKemX + newId.pubKemM)}","signPublicKey":"${enc.encodeToString(newId.pubSign)}"},""" +
            """"wrappedIdentityPrivateKey":{"suiteId":"v1","bytes":"${enc.encodeToString(wrappedNewPriv)}"},""" +
            """"grants":[${grantParts.joinToString(",")}]}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/identity/rotate"))
            .header("Authorization", "Bearer ${session.sessionToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("rotate identity failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }
}
