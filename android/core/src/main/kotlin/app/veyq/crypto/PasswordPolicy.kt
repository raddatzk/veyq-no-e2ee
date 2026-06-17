package app.veyq.crypto

/**
 * Client-side master-password policy. The operator-blind server never sees the password (only an Argon2
 * proof), so length/strength can only be checked on the client. Length is the dominant strength factor
 * (NIST 800-63B); no character-class rules — HIBP covers the breached case. Mirrors web/apple.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10
    private const val STRONG_LENGTH = 16

    fun tooShort(pw: String): Boolean = pw.isNotEmpty() && pw.length < MIN_LENGTH

    /** i18n key for a soft length hint, or null in the acceptable middle (10–15 chars). */
    fun hintKey(pw: String): String? = when {
        pw.isEmpty() -> null
        pw.length < MIN_LENGTH -> "password.minLength"
        pw.length >= STRONG_LENGTH -> "password.strong"
        else -> null
    }
}
