package com.veyq.app.shared.crypto

import com.veyq.app.shared.config.testVeyqConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Guards for the at-rest config-secret seal (ADR 0050). The load-bearing property is that a sealed secret is
 * only ever opaque ciphertext: tampering or a wrong KEK must fail (GCM auth), and an unconfigured KEK must be an
 * error — never a silent plaintext fallback.
 */
class ConfigSecretSealTest {

    private fun sealWith(keyBytes: ByteArray = ByteArray(32) { it.toByte() }): ConfigSecretSeal =
        ConfigSecretSeal(
            testVeyqConfig(mapOf("veyq.security.config-secret-key" to Base64.getEncoder().encodeToString(keyBytes))),
        )

    @Test
    fun sealUnsealRoundTrips() {
        val seal = sealWith()
        val secret = "smtp-p@ssw0rd!"
        val sealed = seal.seal(secret)
        assertNotEquals(secret, sealed.ciphertext, "ciphertext must not be the plaintext")
        assertEquals(secret, seal.unseal(sealed), "unseal must recover the plaintext")
    }

    @Test
    fun eachSealUsesAFreshNonce() {
        val seal = sealWith()
        assertNotEquals(seal.seal("x").nonce, seal.seal("x").nonce, "a fresh nonce per seal")
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val seal = sealWith()
        val tampered = seal.seal("secret").let { it.copy(ciphertext = flipLastByte(it.ciphertext)) }
        assertThrows(AEADBadTagException::class.java) { seal.unseal(tampered) }
    }

    @Test
    fun aWrongKekCannotOpenTheSecret() {
        val sealed = sealWith(ByteArray(32) { 1 }).seal("secret")
        val other = sealWith(ByteArray(32) { 2 })
        assertThrows(AEADBadTagException::class.java) { other.unseal(sealed) }
    }

    @Test
    fun anUnconfiguredKekIsAnErrorNotAPlaintextLeak() {
        val seal = ConfigSecretSeal(testVeyqConfig())
        assertFalse(seal.configured, "no KEK ⇒ not configured")
        assertThrows(IllegalStateException::class.java) { seal.seal("secret") }
    }

    @Test
    fun aMalformedKekFailsLoudlyAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSecretSeal(testVeyqConfig(mapOf("veyq.security.config-secret-key" to Base64.getEncoder().encodeToString(ByteArray(16)))))
        }
    }

    private fun flipLastByte(b64: String): String {
        val bytes = Base64.getDecoder().decode(b64)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }
}
