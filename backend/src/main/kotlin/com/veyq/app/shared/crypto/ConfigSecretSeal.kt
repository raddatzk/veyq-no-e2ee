package com.veyq.app.shared.crypto

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A server-held config secret sealed at rest: opaque AES-256-GCM [ciphertext] + its random [nonce], both base64.
 * Stored as two columns; only a [ConfigSecretSeal] holding the env KEK can open it (ADR 0050).
 */
data class SealedSecret(val ciphertext: String, val nonce: String)

/**
 * Seals server-held **operator config secrets** (first consumer: a tenant's SMTP password, ADR 0048) at rest
 * under a Key-Encryption-Key supplied via the operator's environment (`veyq.security.config-secret-key`, a
 * base64 32-byte key). AES-256-GCM via JCA — standard primitives, no home-grown crypto. The DB holds only
 * ciphertext + nonce, so a database dump *alone* yields no usable secret (ADR 0050); opening needs the env KEK.
 *
 * This is operator-side crypto for the server's OWN infrastructure secrets — distinct from, and no threat to,
 * the client-side content E2EE the server never touches (ADR 0012).
 *
 * Failure posture is deliberate: a present-but-malformed KEK fails **loudly** at construction; an **absent** KEK
 * leaves [configured] false and every [seal]/[unseal] throws — it must never silently fall back to plaintext.
 */
@ApplicationScoped
class ConfigSecretSeal(config: VeyqConfig) {
    private val key: SecretKey? = config.security().configSecretKey()
        .filter { it.isNotBlank() }
        .map { raw ->
            val bytes = Base64.getDecoder().decode(raw)
            require(bytes.size == KEY_BYTES) {
                "veyq.security.config-secret-key must be a base64 $KEY_BYTES-byte key (AES-256); decoded ${bytes.size} bytes"
            }
            SecretKeySpec(bytes, "AES") as SecretKey
        }
        .orElse(null)

    /** True when a KEK is configured; lets a caller fall back (e.g. copy-link) rather than storing a secret. */
    val configured: Boolean get() = key != null

    /** Seal a plaintext secret. Throws if no KEK is configured — never returns the plaintext unsealed. */
    fun seal(plaintext: String): SealedSecret {
        val k = requireKey()
        val nonce = ByteArray(NONCE_BYTES).also(rng::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, k, nonce).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SealedSecret(encoder.encodeToString(ct), encoder.encodeToString(nonce))
    }

    /** Open a sealed secret. Throws on a wrong KEK or tampered ciphertext/nonce (GCM auth), or if no KEK. */
    fun unseal(sealed: SealedSecret): String {
        val k = requireKey()
        val pt = cipher(Cipher.DECRYPT_MODE, k, decoder.decode(sealed.nonce))
            .doFinal(decoder.decode(sealed.ciphertext))
        return String(pt, Charsets.UTF_8)
    }

    private fun requireKey(): SecretKey = key
        ?: error("veyq.security.config-secret-key is not configured — cannot seal/unseal a config secret")

    private fun cipher(mode: Int, k: SecretKey, nonce: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(mode, k, GCMParameterSpec(TAG_BITS, nonce)) }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val rng = SecureRandom()
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }
}
