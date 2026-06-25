package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * uc.spaces.switch-active-space (Android) — the unified space switcher: list every enterable PERSONAL,
 * TENANT_ROOT and GROUP_ROOT space and switch the active context into any of them. Switching fetches no
 * secrets; it opens the chosen scope with the wrap the client already holds. Content ops reuse ScopeContent.
 */
object SwitchActiveSpace {
    private val client = ApiClient.shared

    /** List every PERSONAL, TENANT_ROOT and GROUP_ROOT space the caller can enter. */
    fun listMySpaces(apiBase: String, session: Login.UnlockedSession): JSONArray {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/spaces"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list spaces failed: ${resp.statusCode()}")
        return JSONObject(resp.body()).getJSONArray("spaces")
    }

    /** Switch into a space: open its scope with the already-held wrap (no secrets fetched). */
    fun openSpace(apiBase: String, session: Login.UnlockedSession, entry: JSONObject): ScopeContent.OpenScope = when (entry.getString("kind")) {
        "PERSONAL" -> ScopeContent.openScopeById(apiBase, session, entry.getString("scopeId"), "personal-scope-grant")
        // Every space is opened by scope id; groupId is only for group-management APIs.
        "GROUP" -> ScopeContent.openScopeById(apiBase, session, entry.getString("scopeId"))
        else -> ScopeContent.openScopeById(apiBase, session, entry.getString("scopeId"))
    }
}
