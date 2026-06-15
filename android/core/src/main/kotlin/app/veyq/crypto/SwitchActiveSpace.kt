package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * uc.spaces.switch-active-space (Android) — the unified space switcher: list every space the caller can
 * enter (personal + groups) and switch the active context into any of them. Switching fetches no secrets;
 * it opens the chosen scope with the wrap the client already holds. Content ops reuse ScopeContent.
 */
object SwitchActiveSpace {
    private val client = HttpClient.newHttpClient()

    /** List every space the caller can enter (personal + every group they hold the scope key for). */
    fun listMySpaces(apiBase: String, session: Login.UnlockedSession): JSONArray {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/spaces"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list spaces failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body()).getJSONArray("spaces")
    }

    /** Switch into a space: open its scope with the already-held wrap (no secrets fetched). */
    fun openSpace(apiBase: String, session: Login.UnlockedSession, entry: JSONObject): ScopeContent.OpenScope = when (entry.getString("kind")) {
        "PERSONAL" -> ScopeContent.openPersonalScope(apiBase, session)
        // A group is opened by scope id (no tenant-keyed grant path); a tenant-wide space by its tenant id.
        "GROUP" -> ScopeContent.openScopeById(apiBase, session, entry.getString("scopeId"))
        else -> ScopeContent.openTenantScope(apiBase, session, entry.optString("tenantId").ifEmpty { throw RuntimeException("tenant space entry is missing its tenantId") })
    }
}
