package app.veyq.android.ui

import android.net.Uri
import app.veyq.crypto.SpacePath

/**
 * Parse a member deep link `veyq://open?u=<path>&api=<origin>` (from the web "open in app" banner).
 * The path's space selector + slug logic lives in core's SpacePath (shared + unit-tested).
 */
object HomeRoute {
    data class DeepOpen(val apiBase: String, val path: String)

    /** Extract (apiBase, path) from a veyq://open deep link, or null if it isn't one. */
    fun parseDeepLink(uri: String): DeepOpen? {
        val u = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        if (u.scheme != "veyq" || u.host != "open") return null
        val api = u.getQueryParameter("api")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val path = u.getQueryParameter("u") ?: "/"
        return DeepOpen(api, path)
    }

    fun parsePath(path: String) = SpacePath.parsePath(path)
    fun slugify(name: String) = SpacePath.slugify(name)
}
