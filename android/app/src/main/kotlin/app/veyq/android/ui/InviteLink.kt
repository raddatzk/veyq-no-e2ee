package app.veyq.android.ui

import java.net.URLDecoder

/**
 * Parse a pasted veyq invite link (or bare fragment) into its parts. The bootstrap secret + one-time temp
 * private key ride the URL fragment (`#invite=…&s=…&k=…`) so they never reach a server.
 */
object InviteLink {
    data class Parts(val inviteId: String, val bootstrapSecret: String, val tempPrivateKey: String)

    fun parse(text: String): Parts? {
        val frag = text.trim().substringAfter('#', text.trim())
        val dict = frag.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1]) else null
        }.toMap()
        val id = dict["invite"]; val s = dict["s"]; val k = dict["k"]
        return if (id != null && s != null && k != null) Parts(id, s, k) else null
    }
}
