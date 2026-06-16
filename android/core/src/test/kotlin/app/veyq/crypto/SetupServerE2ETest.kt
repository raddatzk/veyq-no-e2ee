package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `gradle test` no-ops it. */
class SetupServerE2ETest {
    private val apiBase = System.getenv("VEYQ_API_BASE") ?: "http://localhost:8080"
    private val client = HttpClient.newHttpClient()

    private fun get(path: String): String =
        client.send(HttpRequest.newBuilder(URI.create("$apiBase$path")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

    @Test
    fun createsFirstUser() {
        if (System.getenv("VEYQ_E2E") != "1") return // opt-in: requires a live backend

        assertEquals(true, JSONObject(get("/api/setup")).getBoolean("setupRequired"))

        // free text with JSON metacharacters (", \) must be escaped, not corrupt the request body
        val displayName = """Owner "Q" \back"""
        val password = """corr"ect\horse battery"""
        val (body, kit) = SetupServer.registerServerOwner(apiBase, "owner@example.com", displayName, password)
        val user = JSONObject(body)
        assertEquals("owner@example.com", user.getString("email"))
        assertEquals(displayName, user.getString("displayName"))
        assertEquals("ServerOwner", user.getString("serverRole"))
        assertTrue(kit.secretKey.isNotEmpty())

        assertEquals(false, JSONObject(get("/api/setup")).getBoolean("setupRequired"))

        // login with the metacharacter password works (exercises Login's JSON escaping too)
        val session = Login.login(apiBase, "owner@example.com", password, kit.secretKey)
        assertTrue(session.sessionToken.isNotEmpty())
    }
}
