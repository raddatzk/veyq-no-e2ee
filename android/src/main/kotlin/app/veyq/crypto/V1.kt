package app.veyq.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** veyq v1 crypto suite — Android (Kotlin/JVM). Conformance: docs/api/crypto-vectors/v1.json. */
object V1 {
    data class ArgonParams(val t: Int, val m: Int, val p: Int, val dkLen: Int)

    private fun u32be(n: Int): ByteArray = ByteBuffer.allocate(4).putInt(n).array()
    private fun lp(x: ByteArray): ByteArray = u32be(x.size) + x

    fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext) // ciphertext || tag
    }
    fun aeadOpen(key: ByteArray, nonce: ByteArray, aead: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(aead)
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(len)
        gen.generateBytes(out, 0, len)
        return out
    }

    private fun x25519(sk: ByteArray, pk: ByteArray): ByteArray {
        val agr = X25519Agreement()
        agr.init(X25519PrivateKeyParameters(sk, 0))
        val out = ByteArray(agr.agreementSize)
        agr.calculateAgreement(X25519PublicKeyParameters(pk, 0), out, 0)
        return out
    }

    /** Reconstruct the ML-KEM key from the FIPS-203 seed (d || z) via a deterministic RNG. */
    private fun mlkemDecapFromSeed(seed: ByteArray, ctM: ByteArray): ByteArray {
        val kpg = MLKEMKeyPairGenerator()
        kpg.init(MLKEMKeyGenerationParameters(SeededRandom(seed), MLKEMParameters.ml_kem_768))
        val kp = kpg.generateKeyPair()
        return MLKEMExtractor(kp.private as MLKEMPrivateKeyParameters).extractSecret(ctM)
    }

    private fun combiner(ssM: ByteArray, ssX: ByteArray, ctM: ByteArray, epkX: ByteArray, pkM: ByteArray, pkX: ByteArray): ByteArray {
        val ikm = lp(ssM) + lp(ssX)
        val salt = sha256(lp(ctM) + lp(epkX) + lp(pkM) + lp(pkX))
        return hkdf(ikm, salt, "veyq/v1/kem".toByteArray(Charsets.UTF_8), 32)
    }

    fun kemDecap(skX: ByteArray, mlkemSeed: ByteArray, pkX: ByteArray, pkM: ByteArray, ct: ByteArray): ByteArray {
        val epkX = ct.copyOfRange(0, 32)
        val ctM = ct.copyOfRange(32, ct.size)
        val ssX = x25519(skX, epkX)
        val ssM = mlkemDecapFromSeed(mlkemSeed, ctM)
        return combiner(ssM, ssX, ctM, epkX, pkM, pkX)
    }

    fun symWrap(kek: ByteArray, targetKey: ByteArray, context: String, nonce: ByteArray): ByteArray =
        byteArrayOf(0x02) + nonce + aeadSeal(kek, nonce, targetKey, ("v1$context").toByteArray(Charsets.UTF_8))

    fun symUnwrap(kek: ByteArray, blob: ByteArray, context: String): ByteArray {
        val nonce = blob.copyOfRange(1, 1 + 12)
        val aead = blob.copyOfRange(1 + 12, blob.size)
        return aeadOpen(kek, nonce, aead, ("v1$context").toByteArray(Charsets.UTF_8))
    }

    fun asymUnwrap(skX: ByteArray, mlkemSeed: ByteArray, pkX: ByteArray, pkM: ByteArray, blob: ByteArray, context: String): ByteArray {
        val ct = blob.copyOfRange(1, 1 + 1120)
        val nonce = blob.copyOfRange(1 + 1120, 1 + 1120 + 12)
        val aead = blob.copyOfRange(1 + 1120 + 12, blob.size)
        val ss = kemDecap(skX, mlkemSeed, pkX, pkM, ct)
        val kek = hkdf(ss, ByteArray(0), ("veyq/v1/wrap$context").toByteArray(Charsets.UTF_8), 32)
        return aeadOpen(kek, nonce, aead, ("v1$context").toByteArray(Charsets.UTF_8))
    }

    private fun argon2id(input: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.t)
                .withMemoryAsKB(params.m)
                .withParallelism(params.p)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(params.dkLen)
        gen.generateBytes(input, out)
        return out
    }
    fun kekPasswordSecret(secretKey: ByteArray, password: String, salt: ByteArray, params: ArgonParams): ByteArray {
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        return argon2id(lp(secretKey) + lp(nfc), salt, params)
    }
    fun kekRecovery(recoveryCode: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray =
        argon2id(recoveryCode, salt, params)
    fun kekPasskey(prfOutput: ByteArray): ByteArray =
        hkdf(prfOutput, ByteArray(0), "veyq/v1/passkey-kek".toByteArray(Charsets.UTF_8), 32)

    // ---- Identity generation ----
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    data class Identity(
        val pubKemX: ByteArray, val pubKemM: ByteArray, val pubSign: ByteArray,
        val privKemX: ByteArray, val mlkemSeed: ByteArray, val privSign: ByteArray,
    )

    fun generateIdentity(): Identity {
        val xPriv = X25519PrivateKeyParameters(SecureRandom())
        val mlkemSeed = randomBytes(64)
        val kpg = MLKEMKeyPairGenerator()
        kpg.init(MLKEMKeyGenerationParameters(SeededRandom(mlkemSeed), MLKEMParameters.ml_kem_768))
        val mkp = kpg.generateKeyPair()
        val sPriv = Ed25519PrivateKeyParameters(SecureRandom())
        return Identity(
            pubKemX = xPriv.generatePublicKey().encoded,
            pubKemM = (mkp.public as MLKEMPublicKeyParameters).encoded,
            pubSign = sPriv.generatePublicKey().encoded,
            privKemX = xPriv.encoded,
            mlkemSeed = mlkemSeed,
            privSign = sPriv.encoded,
        )
    }

    fun serializeIdentityPrivate(id: Identity): ByteArray = lp(id.privKemX) + lp(id.mlkemSeed) + lp(id.privSign)

    data class IdentityPrivate(val kemX: ByteArray, val mlkemSeed: ByteArray, val sign: ByteArray)

    /** Inverse of serializeIdentityPrivate: read three length-prefixed fields. */
    fun deserializeIdentityPrivate(bytes: ByteArray): IdentityPrivate {
        var o = 0
        fun next(): ByteArray {
            val len = ByteBuffer.wrap(bytes, o, 4).int
            o += 4
            val v = bytes.copyOfRange(o, o + len)
            o += len
            return v
        }
        return IdentityPrivate(next(), next(), next())
    }
}

/** Deterministic RNG that replays a fixed seed — used to reconstruct an ML-KEM key from its seed. */
private class SeededRandom(seed: ByteArray) : SecureRandom() {
    private val buf = seed.copyOf()
    private var pos = 0
    override fun nextBytes(bytes: ByteArray) {
        for (i in bytes.indices) {
            bytes[i] = buf[pos % buf.size]
            pos++
        }
    }
}
