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
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
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
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** veyq v1 crypto suite — Android (Kotlin/JVM). Conformance: docs/api/crypto-vectors/v1.json. */
object V1 {
    data class ArgonParams(val t: Int, val m: Int, val p: Int, val dkLen: Int)

    private fun u32be(n: Int): ByteArray = ByteBuffer.allocate(4).putInt(n).array()
    private fun lp(x: ByteArray): ByteArray = u32be(x.size) + x

    /**
     * kdfParams wire format (crypto agility, AD-25): the unlock cost params travel WITH the wrap so a future
     * cost bump never strands an account minted under lighter params. Layout: u32be t || m || p || dkLen || salt.
     */
    fun encodeKdfParams(p: ArgonParams, salt: ByteArray): ByteArray =
        u32be(p.t) + u32be(p.m) + u32be(p.p) + u32be(p.dkLen) + salt

    fun decodeKdfParams(b: ByteArray): Pair<ArgonParams, ByteArray> {
        val bb = ByteBuffer.wrap(b)
        val params = ArgonParams(bb.int, bb.int, bb.int, bb.int)
        return params to b.copyOfRange(16, b.size)
    }

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

    // AAD binds the envelope TYPE byte (0x02 sym / 0x01 asym) alongside the suite+context, so a ciphertext
    // authenticated for one wrap type can never be reinterpreted as the other (defense in depth vs confusion).
    private fun wrapAad(type: Byte, context: String): ByteArray = byteArrayOf(type) + ("v1$context").toByteArray(Charsets.UTF_8)

    fun symWrap(kek: ByteArray, targetKey: ByteArray, context: String, nonce: ByteArray): ByteArray =
        byteArrayOf(0x02) + nonce + aeadSeal(kek, nonce, targetKey, wrapAad(0x02, context))

    fun symUnwrap(kek: ByteArray, blob: ByteArray, context: String): ByteArray {
        val nonce = blob.copyOfRange(1, 1 + 12)
        val aead = blob.copyOfRange(1 + 12, blob.size)
        return aeadOpen(kek, nonce, aead, wrapAad(0x02, context))
    }

    /** Encapsulate to a recipient's hybrid KEM public key. Randomized (ephemeral X25519 + ML-KEM). */
    fun kemEncap(pkX: ByteArray, pkM: ByteArray): Pair<ByteArray, ByteArray> {
        val esk = X25519PrivateKeyParameters(SecureRandom())
        val epkX = esk.generatePublicKey().encoded
        val agr = X25519Agreement()
        agr.init(esk)
        val ssX = ByteArray(agr.agreementSize)
        agr.calculateAgreement(X25519PublicKeyParameters(pkX, 0), ssX, 0)
        val mPub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pkM)
        val enc = MLKEMGenerator(SecureRandom()).generateEncapsulated(mPub)
        val ss = combiner(enc.secret, ssX, enc.encapsulation, epkX, pkM, pkX)
        return Pair(epkX + enc.encapsulation, ss)
    }

    /** §5a asymmetric wrap: 0x01 || ct(1120) || nonce(12) || aead */
    fun asymWrap(pkX: ByteArray, pkM: ByteArray, targetKey: ByteArray, context: String, nonce: ByteArray): ByteArray {
        val (ct, ss) = kemEncap(pkX, pkM)
        val kek = hkdf(ss, ByteArray(0), ("veyq/v1/wrap$context").toByteArray(Charsets.UTF_8), 32)
        return byteArrayOf(0x01) + ct + nonce + aeadSeal(kek, nonce, targetKey, wrapAad(0x01, context))
    }

    fun asymUnwrap(skX: ByteArray, mlkemSeed: ByteArray, pkX: ByteArray, pkM: ByteArray, blob: ByteArray, context: String): ByteArray {
        val ct = blob.copyOfRange(1, 1 + 1120)
        val nonce = blob.copyOfRange(1 + 1120, 1 + 1120 + 12)
        val aead = blob.copyOfRange(1 + 1120 + 12, blob.size)
        val ss = kemDecap(skX, mlkemSeed, pkX, pkM, ct)
        val kek = hkdf(ss, ByteArray(0), ("veyq/v1/wrap$context").toByteArray(Charsets.UTF_8), 32)
        return aeadOpen(kek, nonce, aead, wrapAad(0x01, context))
    }

    /**
     * Blinded slug token (AD-26): slugKey = HKDF(contentKey, "veyq/v1/slug-token:" + parentSlot);
     * token = HMAC-SHA256(slugKey, NFC(slug)). The key is derived PER PARENT so the same slug under different
     * parents yields different tokens (no cross-parent name-equality leak). parentSlot is the parent node id,
     * or the scope id for a root-level node (matching the server's parent_id sentinel).
     */
    fun slugToken(contentKey: ByteArray, parentSlot: String, slug: String): ByteArray {
        val slugKey = hkdf(contentKey, ByteArray(0), ("veyq/v1/slug-token:$parentSlot").toByteArray(Charsets.UTF_8), 32)
        val nfc = Normalizer.normalize(slug, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(slugKey, "HmacSHA256"))
        return mac.doFinal(nfc)
    }

    /**
     * Media dedup token (AD-09): dedupKey = HKDF(contentKey, "veyq/v1/media-dedup");
     * token = HMAC-SHA256(dedupKey, SHA256(content)). Per-scope + keyed so the blind server can dedup
     * identical media among members without a confirmation-of-file attack (it can't compute the token).
     */
    fun dedupToken(contentKey: ByteArray, content: ByteArray): ByteArray {
        val dedupKey = hkdf(contentKey, ByteArray(0), "veyq/v1/media-dedup".toByteArray(Charsets.UTF_8), 32)
        val hash = MessageDigest.getInstance("SHA-256").digest(content)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(dedupKey, "HmacSHA256"))
        return mac.doFinal(hash)
    }

    /**
     * Recovery commitment (password reset): a domain-separated hash of the master key, stored server-side when
     * the RECOVERY wrap is written. A reset must reproduce it — which needs the master key, i.e. the Secret Key
     * — so an email-token-only attacker cannot hijack the server account. Not a secret (master key is 32 random
     * bytes; the hash is not invertible). Byte-identical to web/iOS so a cross-platform reset verifies.
     */
    fun recoveryAuthTag(masterKey: ByteArray): ByteArray =
        sha256("veyq/v1/recovery-auth".toByteArray(Charsets.UTF_8) + masterKey)

    /**
     * Chunked-AEAD frame (resumable upload + segmented streaming): nonce(12) || AEAD(contentKey, nonce,
     * plaintextChunk, aad) with aad = u32be(index) || u32be(total) — binds the chunk position so a dropped,
     * reordered or appended frame fails to open. Each frame is self-contained (resume / seek per frame).
     */
    private fun frameAad(index: Int, total: Int): ByteArray = u32be(index) + u32be(total)
    fun sealFrame(contentKey: ByteArray, index: Int, total: Int, plaintext: ByteArray, nonce: ByteArray): ByteArray =
        nonce + aeadSeal(contentKey, nonce, plaintext, frameAad(index, total))
    fun openFrame(contentKey: ByteArray, index: Int, total: Int, frame: ByteArray): ByteArray =
        aeadOpen(contentKey, frame.copyOfRange(0, 12), frame.copyOfRange(12, frame.size), frameAad(index, total))

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
