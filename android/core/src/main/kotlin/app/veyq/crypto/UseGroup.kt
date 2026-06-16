package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * uc.spaces.use-group (Android) — enter a group space and collaborate on its shared content. A group is a
 * tenant the caller is an active member of AND holds the scope key for; open scope + content key + items
 * reuse the scope-generic content slice (ScopeContent) — this only adds the group roster.
 */
object UseGroup {
    private val client = HttpClient.newHttpClient()

    /** List the group spaces the caller can enter (active member + holds the scope key). */
    fun listMyGroups(apiBase: String, session: Login.UnlockedSession): JSONArray {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/me/groups"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list groups failed: ${resp.statusCode()}")
        return JSONObject(resp.body()).getJSONArray("groups")
    }
}
