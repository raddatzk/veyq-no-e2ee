package app.veyq.crypto

/**
 * Client-side name search over already-decrypted node names. The server stays blind: callers decode names
 * locally, then pass them through this pure filter.
 */
object Search {
    fun <T> matchByName(items: List<T>, query: String, limit: Int = 25, name: (T) -> String): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return items.filter { name(it).lowercase().contains(q) }.take(limit)
    }
}
