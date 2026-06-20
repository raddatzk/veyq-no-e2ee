package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * uc.account.change-email (Android) — change the account login email. Email is not part of the KEK, so this is
 * a pure identifier swap (no re-wrap). `request` (authenticated) emails a one-time token to the NEW address;
 * `confirm` (token-only) commits the change, so the email link works on any device regardless of login state.
 * Blocking HTTP — call off the main thread.
 */
object EmailChange {
    private val client = ApiClient.shared

    /** Result of requestChange so the caller can show a friendly "already in use" message on 409. */
    class EmailTakenException : RuntimeException("EMAIL_TAKEN")

    fun requestChange(apiBase: String, sessionToken: String, newEmail: String) {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/account/email-change/request"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $sessionToken")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("newEmail", newEmail).toString()))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        when (resp.statusCode()) {
            204 -> return
            409 -> throw EmailTakenException()
            else -> throw RuntimeException("email change request failed: ${resp.statusCode()}")
        }
    }

    /** Confirm an email change with the token from the email link. Token-only — no session required. Returns the new email. */
    fun confirm(apiBase: String, token: String): String {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/account/email-change/confirm"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("token", token).toString()))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("email change confirm failed: ${resp.statusCode()}")
        return JSONObject(resp.body()).getString("email")
    }

    /** Parse a `veyq://email-verify?token=…` deep link, returning the token (or null if it isn't one). Pure
     *  string parsing — `:core` is a plain JVM module without android.net.Uri. Accepts query or fragment. */
    fun parseDeepLink(url: String): String? {
        if (!url.startsWith("veyq://email-verify")) return null
        val params = url.substringAfter('?', "").ifEmpty { url.substringAfter('#', "") }
        return params.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "token") kv[1] else null
        }.firstOrNull()?.ifEmpty { null }
    }
}
