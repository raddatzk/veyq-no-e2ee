package app.veyq.android.ui

import android.net.Uri
import java.text.Normalizer

/**
 * Parse a member deep link `veyq://open?u=<path>&api=<origin>` (from the web "open in app" banner) and the
 * path's space selector. Mirrors the web homeRoute scheme:
 *   tenant   → /urlaube/italien            (no prefix)
 *   group    → /@reisegruppe/urlaube/italien
 *   personal → /~me/urlaube/italien
 */
object HomeRoute {
    data class DeepOpen(val apiBase: String, val path: String)
    data class Parsed(val kind: String, val groupSlug: String?, val segments: List<String>)

    /** Extract (apiBase, path) from a veyq://open deep link, or null if it isn't one. */
    fun parseDeepLink(uri: String): DeepOpen? {
        val u = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        if (u.scheme != "veyq" || u.host != "open") return null
        val api = u.getQueryParameter("api")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val path = u.getQueryParameter("u") ?: "/"
        return DeepOpen(api, path)
    }

    /** Split a path into its space selector + the remaining folder/memory slug segments. */
    fun parsePath(path: String): Parsed {
        val raw = path.split("/").filter { it.isNotEmpty() }
        val first = raw.firstOrNull() ?: return Parsed("TENANT", null, emptyList())
        return when {
            first == "~me" -> Parsed("PERSONAL", null, raw.drop(1))
            first.startsWith("@") -> Parsed("GROUP", first.substring(1), raw.drop(1))
            else -> Parsed("TENANT", null, raw)
        }
    }

    /** A URL-safe slug from a name — must match the web `slugify` so group-name prefixes round-trip. */
    fun slugify(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        val mapped = folded.map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
        val collapsed = mapped.split("-").filter { it.isNotEmpty() }.joinToString("-")
        return collapsed.ifEmpty { "item" }
    }
}
