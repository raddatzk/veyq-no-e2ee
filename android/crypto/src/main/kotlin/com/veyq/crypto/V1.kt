package com.veyq.crypto

import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The veyq `v1` crypto suite — Android (Kotlin/JVM), auth/unlock subset (cr-0014). Byte-identical to
 * `web/src/shared/crypto/v1.ts` and `apple/.../V1.swift`, pinned by `contracts/crypto/vectors/v1.json`
 * (ADR 0009). BouncyCastle for Argon2id / HKDF / Ed25519; the JDK's JCE for AES-256-GCM. The hybrid KEM
 * (ML-KEM-768) and content-side primitives are deferred to the first scope/content slice.
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
}
