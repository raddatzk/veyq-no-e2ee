package app.veyq.crypto

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Asserts the Android (Kotlin/BouncyCastle) v1 suite reproduces the shared vectors byte-for-byte. */
class V1ConformanceTest {
    private val v = JSONObject(File("../docs/api/crypto-vectors/v1.json").readText())

    private fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun argon(o: JSONObject): V1.ArgonParams {
        val p = o.getJSONObject("params")
        return V1.ArgonParams(p.getInt("t"), p.getInt("m"), p.getInt("p"), p.getInt("dkLen"))
    }

    @Test
    fun conformance() {
        assertEquals("v1", v.getString("suite"))

        val aes = v.getJSONObject("primitives").getJSONObject("aes256gcm")
        assertEquals(
            aes.getString("ciphertext_and_tag"),
            hex(V1.aeadSeal(unhex(aes.getString("key")), unhex(aes.getString("nonce")), unhex(aes.getString("plaintext")), unhex(aes.getString("aad")))),
        )

        val kek = v.getJSONObject("kek")
        val pw = kek.getJSONObject("password_secret_argon2id")
        assertEquals(
            pw.getString("kek"),
            hex(V1.kekPasswordSecret(unhex(pw.getString("secret_key")), pw.getString("password_utf8"), unhex(pw.getString("salt")), argon(pw))),
        )
        val rec = kek.getJSONObject("recovery_argon2id")
        assertEquals(rec.getString("kek"), hex(V1.kekRecovery(unhex(rec.getString("recovery_code")), unhex(rec.getString("salt")), argon(rec))))
        val pk = kek.getJSONObject("passkey_prf_hkdf")
        assertEquals(pk.getString("kek"), hex(V1.kekPasskey(unhex(pk.getString("prf_output")))))

        val d = v.getJSONObject("kem_v1").getJSONObject("decap_kat")
        assertEquals(
            d.getString("shared_secret"),
            hex(V1.kemDecap(unhex(d.getString("sk_x25519")), unhex(d.getString("mlkem_seed")), unhex(d.getString("pk_x25519")), unhex(d.getString("pk_mlkem")), unhex(d.getString("ct")))),
        )

        val wrap = v.getJSONObject("wrap")
        val sym = wrap.getJSONObject("sym_master_key")
        assertEquals(
            sym.getString("blob"),
            hex(V1.symWrap(unhex(sym.getString("kek")), unhex(sym.getString("plaintext")), sym.getString("context"), unhex(sym.getString("nonce")))),
        )
        val asym = wrap.getJSONObject("asym_scope_grant")
        assertEquals(
            asym.getString("plaintext"),
            hex(V1.asymUnwrap(unhex(d.getString("sk_x25519")), unhex(d.getString("mlkem_seed")), unhex(asym.getString("pk_x25519")), unhex(asym.getString("pk_mlkem")), unhex(asym.getString("blob")), asym.getString("context"))),
        )
        val slug = v.getJSONObject("slug_token")
        assertEquals(
            slug.getString("token"),
            hex(V1.slugToken(unhex(slug.getString("contentKey")), slug.getString("parentSlot"), slug.getString("slug"))),
        )
        val dd = v.getJSONObject("dedup_token")
        assertEquals(
            dd.getString("token"),
            hex(V1.dedupToken(unhex(dd.getString("contentKey")), unhex(dd.getString("content")))),
        )
        val f = v.getJSONObject("chunk_frame")
        assertEquals(
            f.getString("frame"),
            hex(V1.sealFrame(unhex(f.getString("contentKey")), f.getInt("index"), f.getInt("total"), unhex(f.getString("plaintext")), unhex(f.getString("nonce")))),
        )
        assertEquals(
            f.getString("plaintext"),
            hex(V1.openFrame(unhex(f.getString("contentKey")), f.getInt("index"), f.getInt("total"), unhex(f.getString("frame")))),
        )
    }
}
