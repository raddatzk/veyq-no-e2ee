package com.veyq.app.shared.crypto

import jakarta.enterprise.context.ApplicationScoped
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

/**
 * Verifies an ARGON2_PROOF auth verifier. This is the *only* crypto the server runs on auth material —
 * it validates a password proof and never derives a content key. Params are fixed by suite "v1" and must
 * match the clients' kekRecovery (Argon2id, v1.3, t=3, m=32 MiB, p=1, 32-byte output, salt = first 16
 * bytes in the stored PHC string). See docs/wiki/crosscutting/crypto-spec.md.
 */
@ApplicationScoped
class Argon2Verifier {
    private val phcEncoder = Base64.getEncoder().withoutPadding()
    private val phcDecoder = Base64.getDecoder()
    private val rng = SecureRandom()

    val dummyVerifier: String = phc(ByteArray(saltLen), ByteArray(proofLen))

    fun verifyPassword(password: String, storedVerifier: String): Boolean {
        val parsed = parsePhc(storedVerifier) ?: return false
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val actual = argon2id(nfc, parsed.salt)
        return MessageDigest.isEqual(actual, parsed.proof)
    }

    fun createPasswordVerifier(password: String): String {
        val salt = ByteArray(saltLen)
        rng.nextBytes(salt)
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        return phc(salt, argon2id(nfc, salt))
    }

    fun requirePasswordVerifier(verifier: String) {
        if (parsePhc(verifier) == null) {
            throw IllegalArgumentException("Unsupported ARGON2_PROOF verifier")
        }
    }

    private fun phc(salt: ByteArray, proof: ByteArray): String =
        "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${phcEncoder.encodeToString(salt)}\$${phcEncoder.encodeToString(proof)}"

    private fun parsePhc(storedVerifier: String): StoredVerifier? {
        val parts = storedVerifier.split('$')
        if (parts.size != 6 || parts[0].isNotEmpty()) return null
        if (parts[1] != "argon2id" || parts[2] != "v=19") return null
        val params = parts[3].split(',').associate {
            val pair = it.split('=', limit = 2)
            if (pair.size != 2) return null
            pair[0] to pair[1]
        }
        if (params.size != 3 || params["m"] != memoryKiB.toString() || params["t"] != iterations.toString() || params["p"] != parallelism.toString()) {
            return null
        }
        val salt = decodePhcBase64(parts[4]) ?: return null
        val proof = decodePhcBase64(parts[5]) ?: return null
        if (salt.size != saltLen || proof.size != proofLen) return null
        return StoredVerifier(salt, proof)
    }

    private fun decodePhcBase64(value: String): ByteArray? =
        runCatching {
            phcDecoder.decode(value + "=".repeat((4 - value.length % 4) % 4))
        }.getOrNull()

    private fun argon2id(input: ByteArray, salt: ByteArray): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKiB)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(proofLen)
        gen.generateBytes(input, out)
        return out
    }

    private data class StoredVerifier(val salt: ByteArray, val proof: ByteArray)

    private companion object {
        const val saltLen = 16
        const val proofLen = 32
        const val iterations = 3
        const val memoryKiB = 32768
        const val parallelism = 1
    }
}
