package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * uc.account.connect (Android) — a mobile client points at a chosen instance; this reports whether that
 * instance still needs first-run setup (which is web-only) and the suites it advertises.
 */
object Instance {
    private val client = HttpClient.newHttpClient()

    /** appBaseUrl = the instance's public app origin (for assembling invite links); null on older instances. */
    data class SetupStatus(val setupRequired: Boolean, val suites: List<String>, val appBaseUrl: String?)

    /** Fetch `GET /api/setup` (public, no auth). Blocking — call off the main thread. */
    fun getSetupStatus(apiBase: String): SetupStatus {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create("$apiBase/api/setup")).header("Accept", "application/json").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (resp.statusCode() != 200) throw RuntimeException("setup status failed: ${resp.statusCode()} ${resp.body()}")
        val j = JSONObject(resp.body())
        val arr = j.getJSONArray("suites")
        return SetupStatus(j.getBoolean("setupRequired"), (0 until arr.length()).map { arr.getString(it) }, j.optString("appBaseUrl").ifEmpty { null })
    }
}
