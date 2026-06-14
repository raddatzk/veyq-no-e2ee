package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.uploads.add-media (Android) — get encrypted media into a Memory. The bytes + a light thumbnail derivative
 * are AEAD-sealed under the scope content key; the server stores ciphertext and only low-sensitivity metadata
 * (node, mime, size, takenAt for ordering). Media live only inside a Memory/Chapter.
 */
object AddMedia {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()
    private val EMPTY = ByteArray(0)

    private fun seal(contentKey: ByteArray, plain: ByteArray): String {
        val nonce = V1.randomBytes(12)
        return enc.encodeToString(nonce + V1.aeadSeal(contentKey, nonce, plain, EMPTY))
    }

    /** The dedup token for some content under a scope's content key (matches what the server keyed on). */
    fun dedupTokenFor(contentKey: ByteArray, content: ByteArray): String = enc.encodeToString(V1.dedupToken(contentKey, content))

    /** Has the scope already stored this content? (AD-09 — lets the caller skip re-uploading the bytes.) */
    fun checkMediaBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, dedupToken: String): Boolean {
        val enc = java.net.URLEncoder.encode(dedupToken, "UTF-8")
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/media-blobs/$enc"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("media-blob check failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body()).getBoolean("exists")
    }

    /**
     * Seal a media (+ optional thumbnail) and attach it to a Memory/Chapter node, deduplicating (AD-09): if
     * the scope already stores this content, attach WITHOUT re-uploading the bytes.
     */
    fun addMedia(
        apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String,
        bytes: ByteArray, thumb: ByteArray? = null, takenAt: String, mimeType: String,
    ): JSONObject {
        val dedupToken = dedupTokenFor(contentKey, bytes)
        val alreadyStored = checkMediaBlob(apiBase, session, scopeId, dedupToken)
        val blobPart = if (alreadyStored) "" else ""","sealedBlob":{"suiteId":"v1","bytes":"${seal(contentKey, bytes)}"}"""
        val thumbPart = if (thumb == null) "" else ""","sealedThumb":{"suiteId":"v1","bytes":"${seal(contentKey, thumb)}"}"""
        val json = """{"dedupToken":"$dedupToken","takenAt":"$takenAt","mimeType":"$mimeType","byteSize":${bytes.size}$blobPart$thumbPart}"""
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/media"))
            .header("Authorization", "Bearer ${session.sessionToken}").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("add media failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** List a Memory's media, ordered by takenAt (gallery order). */
    fun listMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String): List<JSONObject> {
        val req = HttpRequest.newBuilder(URI.create("$apiBase/api/scopes/$scopeId/nodes/$nodeId/media"))
            .header("Authorization", "Bearer ${session.sessionToken}").GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw RuntimeException("list media failed: ${resp.statusCode()} ${resp.body()}")
        val arr = JSONObject(resp.body()).getJSONArray("media")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Decrypt a media's sealed bytes (or its thumbnail) with the scope content key. */
    fun decodeBlob(contentKey: ByteArray, blob: JSONObject): ByteArray {
        val sealed = dec.decode(blob.getString("bytes"))
        return V1.aeadOpen(contentKey, sealed.copyOfRange(0, 12), sealed.copyOfRange(12, sealed.size), EMPTY)
    }

    fun hasThumb(item: JSONObject): Boolean = item.has("sealedThumb") && !item.isNull("sealedThumb")
}
