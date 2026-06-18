package app.veyq.crypto

import java.text.Normalizer

/**
 * The space-selector + slug logic for clean paths (`/~me/...`, `/@group/...`, `/tenant/...`). Shared here in
 * core so the slug stays byte-compatible with the web `homeRoute` (group-name prefixes must round-trip) and is
 * unit-tested. The URL/deep-link extraction itself stays per-platform (URLComponents / android.net.Uri).
 */
object SpacePath {
    data class Parsed(val kind: String, val groupSlug: String?, val segments: List<String>)

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
