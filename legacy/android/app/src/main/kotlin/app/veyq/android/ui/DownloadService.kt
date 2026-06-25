package app.veyq.android.ui

import android.content.Context
import app.veyq.crypto.Browse
import app.veyq.crypto.Login
import app.veyq.crypto.Media
import app.veyq.crypto.MemoryTree
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Build a zip of a memory's decrypted originals, fully client-side (the server holds only ciphertext).
 * "Mit Kapiteln" walks the memory's CHAPTER descendants. Blocking — call off the main thread.
 */
object DownloadService {
    fun buildZip(context: Context, apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, name: String, withChapters: Boolean): File {
        val files = mutableListOf<Pair<String, ByteArray>>()
        gather(apiBase, session, scopeId, contentKey, nodeId, "", withChapters, files)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zipFile = File(dir, "${sanitize(name)}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for ((entryName, data) in files) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun gather(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: ByteArray, nodeId: String, prefix: String, withChapters: Boolean, out: MutableList<Pair<String, ByteArray>>) {
        val media = Media.listMedia(apiBase, session, scopeId, nodeId)
        media.forEachIndexed { i, m ->
            val orig = m.getJSONObject("original")
            val ref = Media.BlobRef(orig.getString("blobId"), orig.getInt("totalChunks"), orig.getLong("byteSize"))
            val data = Media.downloadBlob(apiBase, session, scopeId, contentKey, ref)
            out.add("$prefix${"%03d".format(i + 1)}.${ext(m.getString("mimeType"))}" to data)
        }
        if (withChapters) {
            for (c in Browse.listChildren(apiBase, session, scopeId, nodeId).filter { it.getString("type") == "CHAPTER" }) {
                val cname = runCatching { MemoryTree.decodeNodeName(contentKey, c) }.getOrDefault("Kapitel")
                gather(apiBase, session, scopeId, contentKey, c.getString("id"), "$prefix${sanitize(cname)}/", true, out)
            }
        }
    }

    private fun ext(mime: String): String = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "video/mp4" to "mp4", "video/quicktime" to "mov",
    )[mime] ?: "bin"

    private fun sanitize(s: String): String = s.replace("/", "_").ifBlank { "memory" }
}
