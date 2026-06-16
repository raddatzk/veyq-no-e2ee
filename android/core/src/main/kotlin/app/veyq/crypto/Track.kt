package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.memory.track (Android) — a Memory's GPX tracks are {name, points} routes sealed under the scope content
 * key (nonce || AEAD), the same envelope as node names and the journal. The server stores ciphertext only;
 * we seal before POST and open after GET. Route location data never leaves the client in the clear.
 */
object Track {
    private val client = HttpClient.newHttpClient()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class Point(val lat: Double, val lng: Double)
    data class TrackItem(val id: String, val name: String, val points: List<Point>)

    /** List + decrypt a memory's tracks. */
    fun getTracks(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String): List<TrackItem> {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/tracks"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list tracks failed: ${resp.statusCode()}")
        val arr = JSONObject(resp.body()).getJSONArray("tracks")
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val sealed = dec.decode(t.getJSONObject("sealedContent").getString("bytes"))
            val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
            val route = JSONObject(String(pt, Charsets.UTF_8))
            val pts = route.getJSONArray("points")
            TrackItem(
                t.getString("id"),
                route.optString("name", "Track"),
                (0 until pts.length()).map { j -> pts.getJSONObject(j).let { Point(it.getDouble("lat"), it.getDouble("lng")) } },
            )
        }
    }

    /** Seal a parsed GPX route and store it as a new track. */
    fun createTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String, points: List<Point>) {
        val pointsJson = JSONArray().apply { points.forEach { put(JSONObject().put("lat", it.lat).put("lng", it.lng)) } }
        val route = JSONObject().put("name", name).put("points", pointsJson).toString()
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, route.toByteArray(Charsets.UTF_8), EMPTY)
        val payload = JSONObject().put("sealedContent", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/tracks"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("create track failed: ${resp.statusCode()}")
    }

    /** Remove a track. */
    fun deleteTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, trackId: String) {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/tracks/$trackId"))
            .header("Authorization", "Bearer ${session.sessionToken}").DELETE().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204 && resp.statusCode() != 200) throw RuntimeException("delete track failed: ${resp.statusCode()}")
    }
}
