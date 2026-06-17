package app.veyq.crypto

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/**
 * Have I Been Pwned (Pwned Passwords) k-anonymity check — a SOFT warning signal, never a gate. Only the first
 * 5 hex chars of the password's SHA-1 leave the device, straight to the HIBP range API (not via our server);
 * the suffix match is computed locally. Fail-open: any error returns 0, so it can neither block sign-up nor
 * reveal that it ran. Mirrors web shared/pwned.ts and apple Pwned.swift (operator-blind). `count` blocks on
 * I/O — call it off the main thread.
 */
object Pwned {
    /** Uppercase hex SHA-1 of the UTF-8 string (HIBP keys passwords on SHA-1). */
    fun sha1HexUpper(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }

    /** Full uppercase SHA-1 + a HIBP range body ("SUFFIX:COUNT" per line) -> breach count (0 if absent). Pure. */
    fun breachCount(hashHexUpper: String, rangeBody: String): Int {
        val suffix = hashHexUpper.uppercase().drop(5)
        for (line in rangeBody.split('\n', '\r')) {
            val c = line.indexOf(':')
            if (c > 0 && line.substring(0, c).trim().uppercase() == suffix) {
                return line.substring(c + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /** How many known breaches a password appears in (0 = none / unavailable). Fail-open. Blocking I/O. */
    fun count(password: String): Int {
        if (password.isEmpty()) return 0
        return try {
            val hash = sha1HexUpper(password)
            val req = HttpRequest.newBuilder(URI.create("https://api.pwnedpasswords.com/range/${hash.take(5)}"))
                .header("Add-Padding", "true").GET().build()
            val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) 0 else breachCount(hash, resp.body())
        } catch (e: Exception) {
            0
        }
    }
}
