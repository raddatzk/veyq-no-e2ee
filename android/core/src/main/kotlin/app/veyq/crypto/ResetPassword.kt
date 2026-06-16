package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.util.Base64

/**
 * uc.account.reset-password (Android) — recover a forgotten password with the Secret Key (Emergency Kit) AND
 * email access. The email delivers a one-time token (email factor); the Secret Key decrypts the RECOVERY
 * master-key wrap (crypto factor). The client unwraps the master key, re-wraps it under the new password and
 * ships the new PASSWORD wrap + auth verifier. The server sees neither secret.
 */
object ResetPassword {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()
    private val argon = V1.ArgonParams(t = 3, m = 65536, p = 1, dkLen = 32)

    private fun post(apiBase: String, path: String, json: String): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI.create("$apiBase$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to resp.body()
    }

    /** Ask the server to email a reset token. Always succeeds (the server never reveals account existence). */
    fun requestReset(apiBase: String, email: String) {
        val (code, body) = post(apiBase, "/api/account/password-reset/request", JSONObject().put("email", email.trim()).toString())
        if (code != 204) throw RuntimeException("reset request failed: $code $body")
    }

    private fun material(apiBase: String, token: String): JSONObject {
        val (code, body) = post(apiBase, "/api/account/password-reset/material", JSONObject().put("token", token).toString())
        if (code != 200) throw RuntimeException("reset material failed: $code $body")
        return JSONObject(body)
    }

    /** The account email behind a reset token (e.g. for display). Throws on an invalid/expired token. */
    fun tokenEmail(apiBase: String, token: String): String = material(apiBase, token).getString("email")

    /**
     * Complete the reset: unwrap the master key with the Secret Key, re-wrap under the new password, submit
     * the new PASSWORD wrap + auth verifier. Returns the account email so the caller can sign in. Throws on a
     * wrong Secret Key (the RECOVERY wrap fails to open).
     */
    fun confirmReset(apiBase: String, token: String, secretKeyB64: String, newPassword: String): String {
        val mat = material(apiBase, token)
        val rec = mat.getJSONObject("recoveryWrap")
        val secretKey = dec.decode(secretKeyB64)
        val (params, salt) = V1.decodeKdfParams(dec.decode(rec.getString("kdfParams")))
        val masterKey = V1.symUnwrap(V1.kekRecovery(secretKey, salt, params), dec.decode(rec.getString("wrappedKey")), "master-key")

        // re-wrap under the NEW password (PASSWORD KEK still folds in the Secret Key — login stays 2-factor)
        val newSalt = V1.randomBytes(16)
        val newWrap = V1.symWrap(V1.kekPasswordSecret(secretKey, newPassword, newSalt, argon), masterKey, "master-key", V1.randomBytes(12))
        val authSalt = V1.randomBytes(16)
        val verifier = V1.kekRecovery(Normalizer.normalize(newPassword, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), authSalt, argon)

        val body = JSONObject()
            .put("token", token)
            .put("masterKeyWrap", JSONObject().put("unlockMethod", "PASSWORD").put("suiteId", "v1").put("wrappedKey", enc.encodeToString(newWrap)).put("kdfParams", enc.encodeToString(V1.encodeKdfParams(argon, newSalt))))
            .put("authVerifier", JSONObject().put("kind", "ARGON2_PROOF").put("suiteId", "v1").put("verifier", enc.encodeToString(authSalt + verifier)))
            // prove Secret-Key possession: reproduce the commitment the server stored for this master key
            .put("recoveryAuthTag", enc.encodeToString(V1.recoveryAuthTag(masterKey)))
            .toString()
        val (code, resp) = post(apiBase, "/api/account/password-reset/confirm", body)
        if (code != 204) throw RuntimeException("reset confirm failed: $code $resp")
        return mat.getString("email")
    }

    /** Read a `#reset=<token>` token out of a pasted reset link or bare fragment, or null. */
    fun parseToken(text: String): String? {
        val frag = text.trim().substringAfter('#', text.trim())
        for (pair in frag.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "reset") return kv[1]
        }
        return null
    }
}
