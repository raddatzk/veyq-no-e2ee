package com.veyq.crypto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Conformance of the Android `v1` auth/unlock subset against the SHARED vectors
 * (`contracts/crypto/vectors/v1.json`, ADR 0009) — the same file the web TS and Apple Swift suites are
 * pinned to, so a green run here completes cross-implementation byte-parity. Includes the hybrid KEM +
 * asymWrap + identity/fingerprint (cr-0025); remaining content-side vectors are added with the content slice.
 */
class V1ConformanceTest {

    private val vectors: JSONObject = JSONObject(findVectorsFile().readText())

    @Test fun hkdfSha256() {
        val v = vectors.getJSONObject("primitives").getJSONObject("hkdf_sha256_rfc5869_case1")
        val okm = V1.hkdfSha256(hex(v, "ikm"), hex(v, "salt"), hex(v, "info"), v.getInt("length"))
        assertContentEquals(hex(v, "okm"), okm)
    }

    @Test fun aes256Gcm() {
        val v = vectors.getJSONObject("primitives").getJSONObject("aes256gcm")
        val sealed = V1.aeadSeal(hex(v, "key"), hex(v, "nonce"), hex(v, "plaintext"), hex(v, "aad"))
        assertContentEquals(hex(v, "ciphertext_and_tag"), sealed)
        assertContentEquals(hex(v, "plaintext"), V1.aeadOpen(hex(v, "key"), hex(v, "nonce"), sealed, hex(v, "aad")))
    }

    @Test fun kekPasswordSecret() {
        val v = vectors.getJSONObject("kek").getJSONObject("password_secret_argon2id")
        assertContentEquals(hex(v, "kek"), V1.kekPasswordSecret(hex(v, "secret_key"), v.getString("password_utf8"), hex(v, "salt"), params(v)))
    }

    @Test fun kekDevicePassword() {
        val v = vectors.getJSONObject("kek").getJSONObject("device_password_argon2id")
        assertContentEquals(hex(v, "kek"), V1.kekDevicePassword(hex(v, "device_factor"), v.getString("password_utf8"), hex(v, "salt"), params(v)))
    }

    @Test fun kekRecovery() {
        for (key in listOf("recovery_argon2id", "recovery_argon2id_m32768")) {
            val v = vectors.getJSONObject("kek").getJSONObject(key)
            assertContentEquals(hex(v, "kek"), V1.kekRecovery(hex(v, "recovery_code"), hex(v, "salt"), params(v)), key)
        }
    }

    @Test fun kdfParamsRoundTrip() {
        val v = vectors.getJSONObject("kdf_params")
        val p = params(v)
        val encoded = V1.encodeKdfParams(p, hex(v, "salt"))
        assertContentEquals(hex(v, "encoded"), encoded)
        val (dp, salt) = V1.decodeKdfParams(encoded)
        assertEquals(listOf(p.t, p.m, p.p, p.dkLen), listOf(dp.t, dp.m, dp.p, dp.dkLen))
        assertContentEquals(hex(v, "salt"), salt)
    }

    @Test fun symWrap() {
        for (key in listOf("sym_master_key", "session_token")) {
            val v = vectors.getJSONObject("wrap").getJSONObject(key)
            val blob = V1.symWrap(hex(v, "kek"), hex(v, "plaintext"), v.getString("context"), hex(v, "nonce"))
            assertContentEquals(hex(v, "blob"), blob, "symWrap vector $key")
            assertContentEquals(hex(v, "plaintext"), V1.symUnwrap(hex(v, "kek"), blob, v.getString("context")), "symUnwrap vector $key")
        }
    }

    @Test fun recoveryAuthTag() {
        val v = vectors.getJSONObject("recovery_auth_tag")
        assertContentEquals(hex(v, "tag"), V1.recoveryAuthTag(hex(v, "masterKey")))
    }

    @Test fun authKeypairRoundTrip() {
        val salt = V1.deriveAuthSalt(ByteArray(16) { 7 }, "  Owner@Example.com ")
        assertEquals(16, salt.size)
        val kp = V1.deriveAuthKeypair("correct horse battery staple", salt)
        assertEquals(32, kp.publicKey.size)
        val msg = "login-nonce".toByteArray()
        val sig = V1.authSign(kp.privateKey, msg)
        assertTrue(V1.authVerify(kp.publicKey, msg, sig))
        assertFalse(V1.authVerify(kp.publicKey, "other".toByteArray(), sig))
    }

    // --- hybrid KEM + asymWrap + identity (cr-0025) ---

    @Test fun kemDecapKat() {
        val d = vectors.getJSONObject("kem_v1").getJSONObject("decap_kat")
        val sk = V1.HybridSecret(hex(d, "sk_x25519"), hex(d, "mlkem_seed"))
        val pk = V1.HybridPublic(hex(d, "pk_x25519"), hex(d, "pk_mlkem"))
        assertContentEquals(hex(d, "shared_secret"), V1.kemDecap(sk, pk, hex(d, "ct")))
    }

    @Test fun asymScopeGrantUnwrap() {
        val a = vectors.getJSONObject("wrap").getJSONObject("asym_scope_grant")
        val d = vectors.getJSONObject("kem_v1").getJSONObject("decap_kat")
        val sk = V1.HybridSecret(hex(d, "sk_x25519"), hex(d, "mlkem_seed"))
        val pk = V1.HybridPublic(hex(a, "pk_x25519"), hex(a, "pk_mlkem"))
        assertContentEquals(hex(a, "plaintext"), V1.asymUnwrap(sk, pk, hex(a, "blob"), a.getString("context")))
    }

    @Test fun asymWrapRoundTrip() {
        val id = V1.generateIdentity()
        val pk = V1.hybridPublic(id.priv)
        val sk = V1.hybridSecret(id.priv)
        val pt = ByteArray(32) { it.toByte() }
        val blob = V1.asymWrap(pk, pt, "scope-grant", ByteArray(12) { it.toByte() })
        assertContentEquals(pt, V1.asymUnwrap(sk, pk, blob, "scope-grant"))
        assertFailsWith<Exception> { V1.asymUnwrap(sk, pk, blob, "other") }
    }

    @Test fun identitySerializeRoundTrip() {
        val id = V1.generateIdentity()
        val priv = V1.deserializeIdentityPrivate(V1.serializeIdentityPrivate(id.priv))
        assertContentEquals(id.priv.kemX, priv.kemX)
        assertContentEquals(id.priv.mlkemSeed, priv.mlkemSeed)
        assertContentEquals(id.priv.sign, priv.sign)
        assertEquals((4 + 32) + (4 + 1184) + (4 + 32), V1.serializeIdentityPublic(id.pub).size)
    }

    @Test fun fingerprint() {
        val f = vectors.getJSONObject("fingerprint")
        val pub = V1.IdentityPublic(hex(f, "kem_x"), hex(f, "kem_m"), hex(f, "sign"))
        assertEquals(f.getString("digits"), V1.fingerprint(pub))
    }

    private fun params(v: JSONObject): V1.ArgonParams {
        val p = v.getJSONObject("params")
        return V1.ArgonParams(p.getInt("t"), p.getInt("m"), p.getInt("p"), p.getInt("dkLen"))
    }

    private fun hex(v: JSONObject, key: String): ByteArray = v.getString(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun findVectorsFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "contracts/crypto/vectors/v1.json")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("contracts/crypto/vectors/v1.json not found")
    }
}
