package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live (VEYQ_E2E=1): registration persists a RECOVERY wrap, login still works, and the login key bag must
 * EXCLUDE the RECOVERY wrap (released only via the email-token-gated reset — email is the 2nd factor).
 */
class ResetPasswordE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"

    @Test
    fun recoveryWrapNotInLoginKeyBag() {
        if (System.getenv("VEYQ_E2E") != "1") return
        val pass = "correct horse battery staple"
        val (_, kit) = SetupServer.registerServerOwner(apiBase, "reset-owner@example.com", "Owner", pass)
        val session = Login.login(apiBase, "reset-owner@example.com", pass, kit.secretKey)
        assertTrue(session.sessionToken.isNotEmpty())

        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("email", "reset-owner@example.com").put("password", pass).toString()))
            .build()
        val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
        val wraps = JSONObject(resp.body()).getJSONObject("keyBag").getJSONArray("masterKeyWraps")
        val methods = (0 until wraps.length()).map { wraps.getJSONObject(it).getString("unlockMethod") }
        assertTrue(methods.contains("PASSWORD"))
        assertFalse(methods.contains("RECOVERY"))
    }
}
