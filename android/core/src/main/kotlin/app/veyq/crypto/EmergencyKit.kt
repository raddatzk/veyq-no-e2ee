package app.veyq.crypto

import org.json.JSONObject
import java.util.Base64

/**
 * The Emergency Kit — the portable token carrying everything needed to sign in again EXCEPT the
 * password (which stays in the user's head). It is generated on the web after registration; on
 * mobile we only ever *consume* it (scan a QR / import the kit file) to pre-fill the login form.
 *
 * Token format is identical across all platforms so a kit produced on the web parses here:
 *   veyqkit:<base64url(JSON {v:1, instance, email, sk})>
 */
object EmergencyKit {
    data class KitData(val instance: String, val email: String, val secretKey: String)

    private const val PREFIX = "veyqkit:"
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val tokenRe = Regex(PREFIX + "([A-Za-z0-9_-]+)")

    /** The compact, machine-readable token (what a QR encodes / a kit file carries). */
    fun payload(data: KitData): String {
        val json = JSONObject().put("v", 1).put("instance", data.instance).put("email", data.email).put("sk", data.secretKey)
        return PREFIX + enc.encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Extract a kit out of any text — a scanned QR string, a `.veyqkit` file, or the bytes of the kit PDF. */
    fun parse(text: String): KitData? {
        val m = tokenRe.find(text) ?: return null
        return try {
            val o = JSONObject(String(dec.decode(m.groupValues[1]), Charsets.UTF_8))
            val email = o.optString("email")
            val sk = o.optString("sk")
            if (o.optInt("v") != 1 || email.isEmpty() || sk.isEmpty()) null
            else KitData(o.optString("instance", ""), email, sk)
        } catch (e: Exception) {
            null
        }
    }
}
