package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.account.login (Android) — authenticate, fetch the wrapped key bag, and unlock the master key
 * locally. The server returns ciphertext only; the master/identity keys are recovered here.
 */
object Login {
    private val argon = V1.ArgonParams(t = 3, m = 8192, p = 1, dkLen = 32)
    private val b64d = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()

    private fun b64url(s: String): ByteArray = b64d.decode(s)

    data class UnlockedSession(
        val sessionToken: String,
        val email: String,
        val serverRole: String,
        val identity: V1.IdentityPrivate,
        val kemPublicKey: ByteArray,
    )

    fun login(apiBase: String, email: String, password: String, secretKeyB64: String): UnlockedSession {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email","password":"$password"}"""))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("login failed: ${resp.statusCode()} ${resp.body()}")

        val body = JSONObject(resp.body())
        val keyBag = body.getJSONObject("keyBag")
        val wraps = keyBag.getJSONArray("masterKeyWraps")
        var passwordWrap: JSONObject? = null
        for (i in 0 until wraps.length()) {
            val w = wraps.getJSONObject(i)
            if (w.getString("unlockMethod") == "PASSWORD") { passwordWrap = w; break }
        }
        val wrap = passwordWrap ?: throw RuntimeException("no PASSWORD unlock wrap in key bag")

        val kek = V1.kekPasswordSecret(b64url(secretKeyB64), password, b64url(wrap.getString("kdfParams")), argon)
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
        )
    }

    /** Returns the response body for the resolved session, or throws on non-200. */
    fun fetchCurrentUser(apiBase: String, sessionToken: String): String {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me"))
            .header("Authorization", "Bearer $sessionToken").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("me failed: ${resp.statusCode()}")
        return resp.body()
    }
}
