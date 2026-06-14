package app.veyq.crypto

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * uc.uploads.resumable-upload (Android) — chunked, resumable media upload. A large media is split into
 * independent AEAD frames (V1.sealFrame, AAD binds index+total); the ciphertext frames go to the server's
 * BlobStore (never the DB). The session survives interruptions: ask which chunks landed, re-send only the
 * missing ones. Download decrypts frame by frame and concatenates.
 */
object ResumableUpload {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val client = HttpClient.newHttpClient()

    /** Split a blob into fixed-size plaintext chunks. */
    fun chunkBytes(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var o = 0
        while (o < bytes.size) { out.add(bytes.copyOfRange(o, minOf(o + chunkSize, bytes.size))); o += chunkSize }
        return if (out.isEmpty()) listOf(ByteArray(0)) else out
    }

    private fun send(uri: String, token: String, method: String, body: String? = null): HttpResponse<String> {
        var b = HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer $token")
        b = when (method) {
            "GET" -> b.GET()
            "PUT" -> b.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body ?: ""))
            else -> b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun createUpload(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String,
                     totalChunks: Int, mimeType: String, byteSize: Long, takenAt: String): JSONObject {
        val body = """{"totalChunks":$totalChunks,"mimeType":"$mimeType","byteSize":$byteSize,"takenAt":"$takenAt"}"""
        val resp = send("$apiBase/api/scopes/$scopeId/nodes/$nodeId/uploads", session.sessionToken, "POST", body)
        if (resp.statusCode() != 200) throw RuntimeException("create upload failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    fun putChunk(apiBase: String, session: Login.UnlockedSession, uploadId: String, index: Int, total: Int, contentKey: ByteArray, plain: ByteArray) {
        val frame = V1.sealFrame(contentKey, index, total, plain, V1.randomBytes(12))
        val body = """{"frame":{"suiteId":"v1","bytes":"${enc.encodeToString(frame)}"}}"""
        val resp = send("$apiBase/api/uploads/$uploadId/chunks/$index", session.sessionToken, "PUT", body)
        if (resp.statusCode() != 204) throw RuntimeException("put chunk $index failed: ${resp.statusCode()} ${resp.body()}")
    }

    fun getProgress(apiBase: String, session: Login.UnlockedSession, uploadId: String): JSONObject {
        val resp = send("$apiBase/api/uploads/$uploadId", session.sessionToken, "GET")
        if (resp.statusCode() != 200) throw RuntimeException("upload progress failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    fun completeUpload(apiBase: String, session: Login.UnlockedSession, uploadId: String): JSONObject {
        val resp = send("$apiBase/api/uploads/$uploadId/complete", session.sessionToken, "POST")
        if (resp.statusCode() != 200) throw RuntimeException("complete upload failed: ${resp.statusCode()} ${resp.body()}")
        return JSONObject(resp.body())
    }

    /** Resume: send only the chunks the server is still missing, then finalize. */
    fun resumeUpload(apiBase: String, session: Login.UnlockedSession, uploadId: String, contentKey: ByteArray, chunks: List<ByteArray>): JSONObject {
        val arr = getProgress(apiBase, session, uploadId).getJSONArray("receivedChunks")
        val have = (0 until arr.length()).map { arr.getInt(it) }.toSet()
        for (i in chunks.indices) if (i !in have) putChunk(apiBase, session, uploadId, i, chunks.size, contentKey, chunks[i])
        return completeUpload(apiBase, session, uploadId)
    }

    /** Download a finalized media: pull each frame, open it (AAD checks position), concatenate. */
    fun downloadResumable(apiBase: String, session: Login.UnlockedSession, uploadId: String, contentKey: ByteArray, totalChunks: Int): ByteArray {
        var out = ByteArray(0)
        for (i in 0 until totalChunks) {
            val resp = send("$apiBase/api/uploads/$uploadId/chunks/$i", session.sessionToken, "GET")
            if (resp.statusCode() != 200) throw RuntimeException("get chunk $i failed: ${resp.statusCode()} ${resp.body()}")
            val frame = dec.decode(JSONObject(resp.body()).getJSONObject("frame").getString("bytes"))
            out += V1.openFrame(contentKey, i, totalChunks, frame)
        }
        return out
    }
}
