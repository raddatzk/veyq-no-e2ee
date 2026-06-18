package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.memory.playlist (Android, ADR 0010 operator-blind) — link streaming playlists to a Memory. The payload
 * {provider, url, title?, note?} is sealed under the scope content key (the server is blind to it); only the
 * benign `featured` flag is server-visible. Provider is detected client-side from the URL host.
 */
object Playlists {
    private val client = ApiClient.shared
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val EMPTY = ByteArray(0)

    enum class Provider(val label: String) {
        SPOTIFY("Spotify"), APPLE_MUSIC("Apple Music"), YOUTUBE("YouTube"), SOUNDCLOUD("SoundCloud"), DEEZER("Deezer"), OTHER("Link");

        companion object {
            fun of(raw: String) = entries.firstOrNull { it.name == raw } ?: OTHER
        }
    }

    data class Item(val id: String, val featured: Boolean, val provider: Provider, val url: String, val title: String?, val note: String?)

    /** Detect the streaming provider from a URL host (best-effort; OTHER for anything else). */
    fun detectProvider(url: String): Provider {
        val host = runCatching { URI.create(url).host?.lowercase() }.getOrNull() ?: return Provider.OTHER
        return when {
            host.contains("spotify.com") -> Provider.SPOTIFY
            host.contains("music.apple.com") -> Provider.APPLE_MUSIC
            host.contains("youtube.com") || host.contains("youtu.be") -> Provider.YOUTUBE
            host.contains("soundcloud.com") -> Provider.SOUNDCLOUD
            host.contains("deezer.com") -> Provider.DEEZER
            else -> Provider.OTHER
        }
    }

    private fun seal(contentKey: ByteArray, provider: Provider, url: String, title: String?, note: String?): JSONObject {
        val payload = JSONObject().put("provider", provider.name).put("url", url)
        if (!title.isNullOrEmpty()) payload.put("title", title)
        if (!note.isNullOrEmpty()) payload.put("note", note)
        val nonce = V1.randomBytes(12)
        val sealed = nonce + V1.aeadSeal(contentKey, nonce, payload.toString().toByteArray(Charsets.UTF_8), EMPTY)
        return JSONObject().put("suiteId", "v1").put("bytes", enc.encodeToString(sealed))
    }

    private fun decode(body: String, contentKey: ByteArray): List<Item> {
        val arr = JSONObject(body).getJSONArray("playlists")
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val sealed = dec.decode(o.getJSONObject("sealedContent").getString("bytes"))
                val pt = V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
                val p = JSONObject(String(pt, Charsets.UTF_8))
                Item(o.getString("id"), o.getBoolean("featured"), Provider.of(p.getString("provider")), p.getString("url"), p.optString("title", null), p.optString("note", null))
            }.getOrNull()
        }
    }

    private fun req(uri: String, token: String) = HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer $token")

    /** List + decrypt a memory's playlists. */
    fun list(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String): List<Item> {
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/playlists", session.sessionToken).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list playlists failed: ${resp.statusCode()}")
        return decode(resp.body(), contentKey)
    }

    /** Seal {provider, url, title?, note?} and create a new playlist. */
    fun create(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, url: String, title: String?, note: String?, featured: Boolean) {
        val body = JSONObject().put("sealedContent", seal(contentKey, detectProvider(url), url, title, note)).put("featured", featured).toString()
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/playlists", session.sessionToken).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("create playlist failed: ${resp.statusCode()}")
    }

    /** Toggle the (at-most-one) featured playlist; the server clears any other featured entry. */
    fun setFeatured(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, playlistId: String, featured: Boolean) {
        val body = JSONObject().put("featured", featured).toString()
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/playlists/$playlistId", session.sessionToken).header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("feature playlist failed: ${resp.statusCode()}")
    }

    fun delete(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, playlistId: String) {
        val resp = client.send(req("$apiBase/api/scopes/$scopeId/nodes/$nodeId/playlists/$playlistId", session.sessionToken).DELETE().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 204 && resp.statusCode() != 200) throw RuntimeException("delete playlist failed: ${resp.statusCode()}")
    }
}
