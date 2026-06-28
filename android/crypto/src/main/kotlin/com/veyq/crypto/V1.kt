package com.veyq.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters

/**
 * The veyq `v1` crypto suite — Android (Kotlin/JVM), auth/unlock subset (cr-0014). Byte-identical to
 * `web/src/shared/crypto/v1.ts` and `apple/.../V1.swift`, pinned by `contracts/crypto/vectors/v1.json`
 * (ADR 0009). BouncyCastle for Argon2id / HKDF / Ed25519 / X25519 / ML-KEM-768; the JDK's JCE for
 * AES-256-GCM. Auth/unlock subset (cr-0014) + the hybrid KEM, asymWrap and identity bundle (cr-0025);
 * remaining content-side primitives are added with the content slice.
 */
object V1 {

    data class ArgonParams(val t: Int, val m: Int, val p: Int, val dkLen: Int)

    /** Auth-path default: m in KiB = 32 MiB (mirrors v1.ts ARGON). */
    val ARGON = ArgonParams(t = 3, m = 32768, p = 1, dkLen = 32)

    // --- byte helpers ---

    fun utf8(s: String): ByteArray = Normalizer.normalize(s, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun u32be(n: Int): ByteArray =
        byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** length-prefixed: u32be(len) || bytes. */
    private fun lp(x: ByteArray): ByteArray = concat(u32be(x.size), x)

    // --- primitives ---

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    /** AES-256-GCM seal → ciphertext || tag (JCE appends the 16-byte tag). */
    fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    // --- Argon2id (BouncyCastle, v1.3) ---

    private fun argon2id(password: ByteArray, salt: ByteArray, params: ArgonParams): ByteArray {
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
        gen.generateBytes(password, out)
        return out
    }

    // --- KDF params wire format (u32be t‖m‖p‖dkLen‖salt) ---

    fun encodeKdfParams(p: ArgonParams, salt: ByteArray): ByteArray =
        concat(u32be(p.t), u32be(p.m), u32be(p.p), u32be(p.dkLen), salt)

    fun decodeKdfParams(b: ByteArray): Pair<ArgonParams, ByteArray> {
        fun be(o: Int): Int =
            (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or
                (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)
        return ArgonParams(be(0), be(4), be(8), be(12)) to b.copyOfRange(16, b.size)
    }

    // --- unlock KEKs ---

    fun kekPasswordSecret(secretKey: ByteArray, password: String, salt: ByteArray, params: ArgonParams = ARGON): ByteArray =
        argon2id(concat(lp(secretKey), lp(utf8(password))), salt, params)

    fun kekRecovery(recoveryCode: ByteArray, salt: ByteArray, params: ArgonParams = ARGON): ByteArray =
        argon2id(recoveryCode, salt, params)

    fun kekDevicePassword(deviceFactor: ByteArray, password: String, salt: ByteArray, params: ArgonParams = ARGON): ByteArray =
        argon2id(concat(lp(deviceFactor), lp(utf8(password))), salt, params)

    // --- symmetric wrap (0x02 ‖ nonce(12) ‖ aead) ---

    private const val SYM_TYPE: Byte = 0x02
    private fun wrapAad(type: Byte, context: String): ByteArray = concat(byteArrayOf(type), utf8("v1$context"))

    fun symWrap(kek: ByteArray, targetKey: ByteArray, context: String, nonce: ByteArray): ByteArray =
        concat(byteArrayOf(SYM_TYPE), nonce, aeadSeal(kek, nonce, targetKey, wrapAad(SYM_TYPE, context)))

    fun symUnwrap(kek: ByteArray, blob: ByteArray, context: String): ByteArray =
        aeadOpen(kek, blob.copyOfRange(1, 13), blob.copyOfRange(13, blob.size), wrapAad(SYM_TYPE, context))

    // --- operator-blind password auth (ADR 0003 / Model B) ---

    fun deriveAuthSalt(instanceSalt: ByteArray, email: String): ByteArray =
        hkdfSha256(utf8(email.trim().lowercase()), instanceSalt, utf8("veyq/v1/auth-salt"), 16)

    data class AuthKeypair(val publicKey: ByteArray, val privateKey: ByteArray)

    fun deriveAuthKeypair(password: String, authSalt: ByteArray, params: ArgonParams = ARGON): AuthKeypair {
        val seed = argon2id(utf8(password), authSalt, params.copy(dkLen = 32))
        val pub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        return AuthKeypair(pub, seed)
    }

    fun authSign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun authVerify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    // --- recovery commitment ---

    fun recoveryAuthTag(masterKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(concat(utf8("veyq/v1/recovery-auth"), masterKey))

    // --- content tokens (cr-0027) ---

    /** Blinded slug token (AD-26): slugKey = HKDF(contentKey, "", "veyq/v1/slug-token:"+parentSlot, 32);
     *  token = HMAC-SHA256(slugKey, NFC(slug)). Per-parent keyed + one-way — the blind server enforces slug
     *  uniqueness per parent without seeing the name. `parentSlot` = parent node id (or scope id at the root). */
    fun slugToken(contentKey: ByteArray, parentSlot: String, slug: String): ByteArray {
        val slugKey = hkdfSha256(contentKey, ByteArray(0), utf8("veyq/v1/slug-token:$parentSlot"), 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(slugKey, "HmacSHA256"))
        return mac.doFinal(utf8(slug))
    }

    // --- Chunked-AEAD frame (resumable upload + segmented streaming, cr-0028) ---
    //
    // A large blob is split into independent, self-contained frames so chunks resume individually and segments
    // decrypt without the whole file. Each frame = nonce(12) || AEAD(contentKey, nonce, plaintextChunk, aad)
    // with aad = u32be(index) || u32be(total), binding the chunk's position so a dropped, reordered or appended
    // frame fails to open (anti-truncation). The media transport seals each chunk under a per-blob K_blob.

    fun sealFrame(contentKey: ByteArray, index: Int, total: Int, plaintext: ByteArray, nonce: ByteArray): ByteArray =
        concat(nonce, aeadSeal(contentKey, nonce, plaintext, concat(u32be(index), u32be(total))))

    fun openFrame(contentKey: ByteArray, index: Int, total: Int, frame: ByteArray): ByteArray =
        aeadOpen(contentKey, frame.copyOfRange(0, 12), frame.copyOfRange(12, frame.size), concat(u32be(index), u32be(total)))

    // --- Hybrid KEM (X25519 + ML-KEM-768) — the scope-grant primitive (cr-0025) ---
    //
    // Mirrors web/Apple byte-for-byte, pinned by `kem_v1` / `wrap.asym_scope_grant`. `HybridSecret.m` is the
    // 64-byte ML-KEM seed; the private key is reconstructed via FIPS 203 keygen fed the seed as randomness
    // (the form BouncyCastle/CryptoKit want). `HybridPublic.m` is the 1184-byte ML-KEM public key.

    data class HybridPublic(val x: ByteArray, val m: ByteArray)
    data class HybridSecret(val x: ByteArray, val m: ByteArray)

    private const val ASYM_TYPE: Byte = 0x01

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** A SecureRandom that streams a fixed seed, so FIPS 203 ML-KEM keygen reconstructs deterministically
     *  from the stored 64-byte seed (d‖z), identical to the other stacks. */
    private class SeedRandom(seed: ByteArray) : SecureRandom() {
        private val buf = seed.copyOf()
        private var off = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) { bytes[i] = if (off < buf.size) buf[off++] else 0 }
        }
    }

    private fun mlkemFromSeed(seed: ByteArray): Pair<MLKEMPublicKeyParameters, MLKEMPrivateKeyParameters> {
        val gen = MLKEMKeyPairGenerator()
        gen.init(MLKEMKeyGenerationParameters(SeedRandom(seed), MLKEMParameters.ml_kem_768))
        val kp = gen.generateKeyPair()
        return (kp.public as MLKEMPublicKeyParameters) to (kp.private as MLKEMPrivateKeyParameters)
    }

    private fun x25519Public(secret: ByteArray): ByteArray =
        X25519PrivateKeyParameters(secret, 0).generatePublicKey().encoded

    private fun x25519Agree(secret: ByteArray, peerPub: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(X25519PrivateKeyParameters(secret, 0))
        val out = ByteArray(a.agreementSize)
        a.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), out, 0)
        return out
    }

    /** HKDF combiner binding both shared secrets + the transcript (info "veyq/v1/kem"). */
    private fun kemCombiner(ssM: ByteArray, ssX: ByteArray, ctM: ByteArray, epkX: ByteArray, pkM: ByteArray, pkX: ByteArray): ByteArray {
        val ikm = concat(lp(ssM), lp(ssX))
        val salt = sha256(concat(lp(ctM), lp(epkX), lp(pkM), lp(pkX)))
        return hkdfSha256(ikm, salt, utf8("veyq/v1/kem"), 32)
    }

    /** Encapsulate to a hybrid public key → (ct = epkX(32)‖mlkemCt(1088), shared secret(32)). */
    fun kemEncap(pk: HybridPublic): Pair<ByteArray, ByteArray> {
        val esk = X25519PrivateKeyParameters(SecureRandom())
        val epkX = esk.generatePublicKey().encoded
        val ssX = x25519Agree(esk.encoded, pk.x)
        val enc = MLKEMGenerator(SecureRandom()).generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pk.m))
        val ssM = enc.secret
        val ctM = enc.encapsulation
        val ss = kemCombiner(ssM, ssX, ctM, epkX, pk.m, pk.x)
        return concat(epkX, ctM) to ss
    }

    /** Decapsulate `ct` with the recipient's hybrid secret (ML-KEM key reconstructed from the seed). */
    fun kemDecap(sk: HybridSecret, pk: HybridPublic, ct: ByteArray): ByteArray {
        require(ct.size >= 32) { "kem ct too short" }
        val epkX = ct.copyOfRange(0, 32)
        val ctM = ct.copyOfRange(32, ct.size)
        val ssX = x25519Agree(sk.x, epkX)
        val (_, mlSk) = mlkemFromSeed(sk.m)
        val ssM = MLKEMExtractor(mlSk).extractSecret(ctM)
        return kemCombiner(ssM, ssX, ctM, epkX, pk.m, pk.x)
    }

    /** §5a asymmetric wrap: 0x01 ‖ ct(1120) ‖ nonce(12) ‖ aead; KEK = HKDF(ss, "", "veyq/v1/wrap"+context). */
    fun asymWrap(pk: HybridPublic, targetKey: ByteArray, context: String, nonce: ByteArray): ByteArray {
        val (ct, ss) = kemEncap(pk)
        val kek = hkdfSha256(ss, ByteArray(0), utf8("veyq/v1/wrap$context"), 32)
        return concat(byteArrayOf(ASYM_TYPE), ct, nonce, aeadSeal(kek, nonce, targetKey, wrapAad(ASYM_TYPE, context)))
    }

    fun asymUnwrap(sk: HybridSecret, pk: HybridPublic, blob: ByteArray, context: String): ByteArray {
        require(blob.size >= 1 + 1120 + 12 + 16) { "asym blob too short" }
        val ct = blob.copyOfRange(1, 1 + 1120)
        val nonce = blob.copyOfRange(1 + 1120, 1 + 1120 + 12)
        val aead = blob.copyOfRange(1 + 1120 + 12, blob.size)
        val ss = kemDecap(sk, pk, ct)
        val kek = hkdfSha256(ss, ByteArray(0), utf8("veyq/v1/wrap$context"), 32)
        return aeadOpen(kek, nonce, aead, wrapAad(ASYM_TYPE, context))
    }

    // --- Identity (hybrid KEM keypair + Ed25519 signing) — cr-0025 ---

    data class IdentityPublic(val kemX: ByteArray, val kemM: ByteArray, val sign: ByteArray)
    data class IdentityPrivate(val kemX: ByteArray, val mlkemSeed: ByteArray, val sign: ByteArray)
    data class Identity(val pub: IdentityPublic, val priv: IdentityPrivate)

    /** HybridSecret for kemDecap/asymUnwrap from a stored private bundle (m = the 64-byte seed). */
    fun hybridSecret(priv: IdentityPrivate): HybridSecret = HybridSecret(priv.kemX, priv.mlkemSeed)

    /** The hybrid PUBLIC bundle re-derived from a stored private bundle — for sealing to one's own identity. */
    fun hybridPublic(priv: IdentityPrivate): HybridPublic =
        HybridPublic(x25519Public(priv.kemX), mlkemFromSeed(priv.mlkemSeed).first.encoded)

    /** Generate a fresh identity (hybrid KEM keypair + Ed25519 signing). Stored private form is the seeds. */
    fun generateIdentity(): Identity {
        val rnd = SecureRandom()
        val kemXsk = X25519PrivateKeyParameters(rnd)
        val seed = ByteArray(64).also { rnd.nextBytes(it) }
        val mlPub = mlkemFromSeed(seed).first
        val signSk = Ed25519PrivateKeyParameters(rnd)
        return Identity(
            IdentityPublic(kemXsk.generatePublicKey().encoded, mlPub.encoded, signSk.generatePublicKey().encoded),
            IdentityPrivate(kemXsk.encoded, seed, signSk.encoded),
        )
    }

    fun serializeIdentityPublic(pub: IdentityPublic): ByteArray = concat(lp(pub.kemX), lp(pub.kemM), lp(pub.sign))

    fun serializeIdentityPrivate(priv: IdentityPrivate): ByteArray = concat(lp(priv.kemX), lp(priv.mlkemSeed), lp(priv.sign))

    /** Inverse of serializeIdentityPrivate: three length-prefixed fields (guards untrusted bytes). */
    fun deserializeIdentityPrivate(bytes: ByteArray): IdentityPrivate {
        var o = 0
        fun next(): ByteArray {
            require(o + 4 <= bytes.size) { "truncated identity bundle" }
            val len = (bytes[o].toInt() and 0xff shl 24) or (bytes[o + 1].toInt() and 0xff shl 16) or
                (bytes[o + 2].toInt() and 0xff shl 8) or (bytes[o + 3].toInt() and 0xff)
            o += 4
            require(len in 0..(bytes.size - o)) { "bad field length" }
            val v = bytes.copyOfRange(o, o + len)
            o += len
            return v
        }
        return IdentityPrivate(next(), next(), next())
    }

    /** Identity fingerprint ("safety number"): SHA-256("veyq/v1/fingerprint"‖serializeIdentityPublic),
     *  6 groups of 5 decimal digits (40-bit big-endian chunks mod 100000). Verification only. */
    fun fingerprint(pub: IdentityPublic): String {
        val h = sha256(concat(utf8("veyq/v1/fingerprint"), serializeIdentityPublic(pub)))
        return (0 until 6).joinToString(" ") { i ->
            var v = 0L
            for (j in 0 until 5) v = v * 256 + (h[i * 5 + j].toInt() and 0xff)
            (v % 100000).toString().padStart(5, '0')
        }
    }
}
