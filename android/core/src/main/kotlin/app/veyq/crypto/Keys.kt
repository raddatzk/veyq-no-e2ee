package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * uc.keys.rewrap-scope-grant (Android) — replace only this user's existing wrapped grant.
 */
object Keys {
    private val client = HttpClient.newHttpClient()

    fun upsertMyMasterKeyWrap(apiBase: String, sessionToken: String, masterKeyWrap: JSONObject): JSONObject {
        val unlockMethod = URLEncoder.encode(masterKeyWrap.getString("unlockMethod"), StandardCharsets.UTF_8)
        val body = JSONObject().put("masterKeyWrap", masterKeyWrap).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/key-wraps/$unlockMethod"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $sessionToken")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("master key wrap upsert failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    fun removeMyMasterKeyWrap(apiBase: String, sessionToken: String, unlockMethod: String, credentialRef: String? = null) {
        val encodedMethod = URLEncoder.encode(unlockMethod, StandardCharsets.UTF_8)
        val query = if (credentialRef == null) "" else "?credentialRef=${URLEncoder.encode(credentialRef, StandardCharsets.UTF_8)}"
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/key-wraps/$encodedMethod$query"))
            .header("Authorization", "Bearer $sessionToken")
            .DELETE()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204) throw RuntimeException("master key wrap removal failed: ${resp.statusCode()}")
    }

    fun rewrapMyScopeGrant(apiBase: String, sessionToken: String, scopeId: String, wrappedScopePrivateKey: JSONObject): JSONObject {
        val encodedScopeId = URLEncoder.encode(scopeId, StandardCharsets.UTF_8)
        val body = JSONObject().put("wrappedScopePrivateKey", wrappedScopePrivateKey).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$encodedScopeId/grants/me"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $sessionToken")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("scope grant rewrap failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }
}
