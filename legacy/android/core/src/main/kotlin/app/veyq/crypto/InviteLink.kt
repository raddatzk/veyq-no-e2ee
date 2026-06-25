package app.veyq.crypto

import java.net.URLDecoder

/**
 * Parse / build a veyq invite link. The bootstrap secret + the one-time temp key ride the URL fragment
 * (`#invite=…&s=…&…`) so they never reach a server. Two modes share the plumbing:
 *  - pragmatic  → `k=<tempPrivKey>` (the temp key in the clear)
 *  - passphrase → `ek=<encTempKey>&ps=<salt>` (the temp key encrypted under an out-of-band passphrase)
 * Shared pure logic (no crypto / UI deps) — kept in core so it is unit-tested alongside the other
 * cross-platform conformance logic, byte-compatible with the web and iOS parsers.
 */
object InviteLink {
    data class Parts(
        val inviteId: String,
        val bootstrapSecret: String,
        val tempPrivateKey: String?,   // pragmatic
        val encTempKey: String?,       // passphrase-protected
        val pwSalt: String?,
    ) {
        val isProtected: Boolean get() = encTempKey != null && pwSalt != null
    }
    /** A deep link from the browser banner: the invite parts plus the instance it points at. */
    data class DeepLink(val apiBase: String, val parts: Parts)

    private fun dictOf(text: String): Map<String, String> {
        val frag = text.trim().substringAfter('#', text.trim())
        return frag.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1]) else null
        }.toMap()
    }

    fun parse(text: String): Parts? {
        val dict = dictOf(text)
        val id = dict["invite"] ?: return null
        val s = dict["s"] ?: return null
        dict["k"]?.let { return Parts(id, s, it, null, null) }
        val ek = dict["ek"]; val ps = dict["ps"]
        return if (ek != null && ps != null) Parts(id, s, null, ek, ps) else null
    }

    /** Parse a veyq://invite#invite=…&s=…&(k|ek+ps)…&api=<origin> deep link (the banner carries the instance). */
    fun parseDeepLink(uri: String): DeepLink? {
        val parts = parse(uri) ?: return null
        val api = dictOf(uri)["api"]?.takeIf { it.isNotBlank() } ?: return null
        return DeepLink(api.trimEnd('/'), parts)
    }

    // The fragment values are base64url (chars [A-Za-z0-9-_]) — already URL-fragment-safe, so plain joins.
    fun build(origin: String, inviteId: String, bootstrapSecret: String, tempPrivateKey: String): String =
        "${origin.trimEnd('/')}/#invite=$inviteId&s=$bootstrapSecret&k=$tempPrivateKey"

    fun buildProtected(origin: String, inviteId: String, bootstrapSecret: String, encTempKey: String, pwSalt: String): String =
        "${origin.trimEnd('/')}/#invite=$inviteId&s=$bootstrapSecret&ek=$encTempKey&ps=$pwSalt"
}
