package app.veyq.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.memory.add-map-tracks (Android) — a Memory's GPX tracks are {name, points} routes sealed under the scope content
 * key (nonce || AEAD), the same envelope as node names and the journal. The server stores ciphertext only;
 * we seal before POST and open after GET. Route location data never leaves the client in the clear.
 */
object Track {
    private val client = ApiClient.shared
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    data class Point(val lat: Double, val lng: Double, val ele: Double? = null, val time: String? = null)
    data class TrackItem(val id: String, val name: String, val points: List<Point>)

    /**
     * Track statistics derived from a route's points: distance always; ascent/descent when elevations are
     * present; duration when timestamps are present. Missing fields → that stat is omitted, never wrong.
     * Mirrored on web/iOS.
     */
    data class Stats(val distanceM: Double, val ascentM: Double?, val descentM: Double?, val durationS: Long?)

    private const val EARTH_RADIUS_M = 6_371_000.0

    private fun haversine(a: Point, b: Point): Double {
        fun rad(d: Double) = d * Math.PI / 180
        val dLat = rad(b.lat - a.lat)
        val dLng = rad(b.lng - a.lng)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }

    fun stats(points: List<Point>): Stats {
        var distanceM = 0.0; var ascentM = 0.0; var descentM = 0.0; var haveEle = false
        for (i in 1 until points.size) {
            distanceM += haversine(points[i - 1], points[i])
            val a = points[i - 1].ele; val b = points[i].ele
            if (a != null && b != null) {
                haveEle = true
                val d = b - a
                if (d > 0) ascentM += d else descentM -= d
            }
        }
        val times = points.mapNotNull { it.time }
        var durationS: Long? = null
        if (times.isNotEmpty()) {
            val start = parseIso(times.first()); val end = parseIso(times.last())
            if (start != null && end != null && end > start) durationS = (end - start) / 1000
        }
        return Stats(distanceM, if (haveEle) ascentM else null, if (haveEle) descentM else null, durationS)
    }

    private fun parseIso(s: String): Long? = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()

    /** "850 m" under 1 km, otherwise "12.3 km". */
    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${Math.round(meters)} m" else String.format(java.util.Locale.US, "%.1f km", meters / 1000)

    /** "1 h 23 min" / "23 min" / "45 s". */
    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> "$seconds s"
        }
    }

    /** "↑120 m ↓95 m" (rounded), or empty when no elevation data. */
    fun formatElevation(s: Stats): String {
        val ascent = s.ascentM ?: return ""
        return "↑${Math.round(ascent)} m ↓${Math.round(s.descentM ?: 0.0)} m"
    }

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
                (0 until pts.length()).map { j ->
                    pts.getJSONObject(j).let { Point(it.getDouble("lat"), it.getDouble("lng"), if (it.has("ele")) it.getDouble("ele") else null, if (it.has("time")) it.optString("time").ifEmpty { null } else null) }
                },
            )
        }
    }

    /** Seal a parsed GPX route and store it as a new track. */
    fun createTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String, points: List<Point>) {
        val pointsJson = JSONArray().apply {
            points.forEach {
                val o = JSONObject().put("lat", it.lat).put("lng", it.lng)
                if (it.ele != null) o.put("ele", it.ele)
                if (it.time != null) o.put("time", it.time)
                put(o)
            }
        }
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

    /** Rename (re-seal) a track: re-seal {name, points} (new name, same points) and PATCH; the id is preserved. */
    fun updateTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, trackId: String, name: String, points: List<Point>) {
        val pointsJson = JSONArray().apply {
            points.forEach {
                val o = JSONObject().put("lat", it.lat).put("lng", it.lng)
                if (it.ele != null) o.put("ele", it.ele)
                if (it.time != null) o.put("time", it.time)
                put(o)
            }
        }
        val route = JSONObject().put("name", name).put("points", pointsJson).toString()
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, route.toByteArray(Charsets.UTF_8), EMPTY)
        val payload = JSONObject().put("sealedContent", JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))).toString()
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/tracks/$trackId"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(payload)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("rename track failed: ${resp.statusCode()}")
    }

    /** Remove a track. */
    fun deleteTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, trackId: String) {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/tracks/$trackId"))
            .header("Authorization", "Bearer ${session.sessionToken}").DELETE().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204 && resp.statusCode() != 200) throw RuntimeException("delete track failed: ${resp.statusCode()}")
    }
}
