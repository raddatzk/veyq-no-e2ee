package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.account.login (Android) — authenticate, fetch the wrapped key bag, and unlock the master key
 * locally. The server returns ciphertext only; the master/identity keys are recovered here.
 */
object Login {
    private val b64d = Base64.getUrlDecoder()
    private val client = ApiClient.shared

    private fun b64url(s: String): ByteArray = b64d.decode(s)

    data class UnlockedSession(
        val sessionToken: String,
        val email: String,
        val serverRole: String,
        val identity: V1.IdentityPrivate,
        val kemPublicKey: ByteArray,
        val masterKey: ByteArray,
    )

    fun login(apiBase: String, email: String, password: String, secretKeyB64: String): UnlockedSession {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"email":${JSONObject.quote(email)},"password":${JSONObject.quote(password)}}"""))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("login failed: ${resp.statusCode()}")

        val body = JSONObject(resp.body())
        val keyBag = body.getJSONObject("keyBag")
        val wraps = keyBag.getJSONArray("masterKeyWraps")
        var passwordWrap: JSONObject? = null
        for (i in 0 until wraps.length()) {
            val w = wraps.getJSONObject(i)
            if (w.getString("unlockMethod") == "PASSWORD") { passwordWrap = w; break }
        }
        val wrap = passwordWrap ?: throw RuntimeException("no PASSWORD unlock wrap in key bag")

        // the unlock cost params travel with the wrap, so a future cost bump never strands this account
        val (params, salt) = V1.decodeKdfParams(b64url(wrap.getString("kdfParams")))
        val kek = V1.kekPasswordSecret(b64url(secretKeyB64), password, salt, params)
        val masterKey = V1.symUnwrap(kek, b64url(wrap.getString("wrappedKey")), "master-key")
        val privBytes = V1.symUnwrap(masterKey, b64url(keyBag.getJSONObject("wrappedIdentityPrivateKey").getString("bytes")), "identity-priv")
        val identity = V1.deserializeIdentityPrivate(privBytes)

        val user = body.getJSONObject("user")
        return UnlockedSession(
            sessionToken = body.getString("sessionToken"),
            email = user.getString("email"),
            serverRole = user.getString("serverRole"),
            identity = identity,
            kemPublicKey = b64url(keyBag.getJSONObject("identityPublicKey").getString("kemPublicKey")),
            masterKey = masterKey,
        )
    }

    /** Logout: invalidate the current session token server-side. Idempotent for an already-dead token. */
    fun logout(apiBase: String, sessionToken: String) {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/session"))
            .header("Authorization", "Bearer $sessionToken").DELETE().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204) throw RuntimeException("logout failed: ${resp.statusCode()}")
    }

    /** Returns the response body for the resolved session, or throws on non-200. */
    fun fetchCurrentUser(apiBase: String, sessionToken: String): String {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me"))
            .header("Authorization", "Bearer $sessionToken").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("me failed: ${resp.statusCode()}")
        return resp.body()
    }

    /** Returns the authenticated user's unshared personal scope grant, or throws on non-200. */
    fun fetchPersonalScope(apiBase: String, sessionToken: String): JSONObject {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/personal-scope"))
            .header("Authorization", "Bearer $sessionToken").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("personal scope failed: ${resp.statusCode()}")
        return JSONObject(resp.body())
    }

    /** Unwrap the personal scope grant with the holder's identity key. */
    fun openPersonalScopeGrant(grant: JSONObject, identity: V1.IdentityPrivate, identityKemPublicKey: ByteArray): V1.IdentityPrivate {
        val pkX = identityKemPublicKey.copyOfRange(0, 32)
        val pkM = identityKemPublicKey.copyOfRange(32, identityKemPublicKey.size)
        val wrapped = b64d.decode(grant.getJSONObject("wrappedScopePrivateKey").getString("bytes"))
        val scopePrivBytes = V1.asymUnwrap(identity.kemX, identity.mlkemSeed, pkX, pkM, wrapped, "personal-scope-grant")
        return V1.deserializeIdentityPrivate(scopePrivBytes)
    }
}
