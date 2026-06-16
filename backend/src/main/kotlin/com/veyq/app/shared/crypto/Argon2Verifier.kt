package com.veyq.app.shared.crypto

import jakarta.enterprise.context.ApplicationScoped
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Verifies an ARGON2_PROOF auth verifier. This is the *only* crypto the server runs on auth material —
 * it validates a password proof and never derives a content key. Params are fixed by suite "v1" and must
 * match the clients' kekRecovery (Argon2id, v1.3, t=3, m=32 MiB, p=1, 32-byte output, salt = first 16
 * bytes of the stored verifier). See docs/wiki/crosscutting/crypto-spec.md.
 */
@ApplicationScoped
class Argon2Verifier {
    private val saltLen = 16
    private val proofLen = 32

    fun verifyPassword(password: String, storedVerifier: ByteArray): Boolean {
        if (storedVerifier.size != saltLen + proofLen) return false
        val salt = storedVerifier.copyOfRange(0, saltLen)
        val expected = storedVerifier.copyOfRange(saltLen, saltLen + proofLen)
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val actual = argon2id(nfc, salt)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun argon2id(input: ByteArray, salt: ByteArray): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(32768)
                .withParallelism(1)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(proofLen)
        gen.generateBytes(input, out)
        return out
    }
}
