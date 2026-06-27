package com.veyq.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.veyq.client.DeviceLevel
import com.veyq.client.DeviceSecurityStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed [DeviceSecurityStore] (cr-0021 L1; cr-0022 L2/L3). L1's `deviceFactor` is derived
 * from a **non-extractable AndroidKeyStore HMAC-SHA256 key** (StrongBox where available) signing a fixed
 * constant — the web-parity backing (the key bytes never enter the heap). Only the level / wrapId / refresh
 * token are persisted in private `SharedPreferences`; the L2/L3 random key `K` storage (biometric-gated
 * Keystore-AES for L2, EncryptedSharedPreferences for L3) is added in cr-0022 phase 3.
 */
class KeystoreDeviceSecurityStore(context: Context) : DeviceSecurityStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun currentLevel(email: String): DeviceLevel? =
        prefs.getString(levelKey(email), null)?.let { runCatching { DeviceLevel.valueOf(it) }.getOrNull() }

    override fun wrapId(email: String): String? = prefs.getString(wrapKey(email), null)

    override fun refreshToken(email: String): String? = prefs.getString(tokenKey(email), null)

    override fun updateRefreshToken(email: String, token: String?) {
        prefs.edit().apply { if (token == null) remove(tokenKey(email)) else putString(tokenKey(email), token) }.apply()
    }

    override fun beginEnrol(email: String): ByteArray {
        ensureKey(email)
        return deviceFactor(email)!!
    }

    override fun deviceFactor(email: String): ByteArray? {
        val ks = keystore()
        if (!ks.containsAlias(alias(email))) return null
        val key = ks.getKey(alias(email), null) as SecretKey
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(DEVICE_BIND.toByteArray(Charsets.UTF_8)) }
    }

    override fun commitOnlyPassword(email: String, wrapId: String) {
        prefs.edit().putString(levelKey(email), DeviceLevel.ONLY_PASSWORD.name).putString(wrapKey(email), wrapId).apply()
    }

    override fun commitPasswordless(email: String, level: DeviceLevel, wrapId: String, refreshToken: String) {
        prefs.edit()
            .putString(levelKey(email), level.name)
            .putString(wrapKey(email), wrapId)
            .putString(tokenKey(email), refreshToken)
            .apply()
    }

    override fun clear(email: String) {
        prefs.edit().remove(levelKey(email)).remove(wrapKey(email)).remove(tokenKey(email)).remove(ctKey(email)).apply()
        runCatching { keystore().deleteEntry(alias(email)) }
        runCatching { keystore().deleteEntry(aesAlias(email, gated = false)) }
        runCatching { keystore().deleteEntry(aesAlias(email, gated = true)) }
    }

    // MARK: - L2/L3 random key K storage (cr-0022)
    //
    // K (the wrap key) is AES-GCM-encrypted by a Keystore AES key and the ciphertext is kept in prefs.
    // L3 uses an **ungated** key (read synchronously). L2 uses a **biometric-gated** key
    // (`setUserAuthenticationRequired`) — encrypt + decrypt both run inside a `BiometricPrompt` via a
    // `CryptoObject`, so the `:app` layer drives those through [encryptCipherL2] / [finishStoreKeyL2] and
    // [decryptCipherL2] / [finishReadKeyL2]. The key bytes never persist in the clear.

    /** L3: encrypt + store K under the **ungated** Keystore key (synchronous). */
    fun storeKeyUngated(email: String, k: ByteArray) {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, ensureAesKey(email, gated = false)) }
        putCt(email, cipher.iv, cipher.doFinal(k))
    }

    /** L3: read + decrypt K from the ungated key, or null if absent/unusable. */
    fun readKeyUngated(email: String): ByteArray? {
        val (iv, ct) = readCt(email) ?: return null
        val key = existingAesKey(email, gated = false) ?: return null
        return runCatching {
            Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(ct)
        }.getOrNull()
    }

    /** L2 enrol — an ENCRYPT cipher on the gated key, to authenticate inside a `BiometricPrompt`. */
    fun encryptCipherL2(email: String): Cipher =
        Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, ensureAesKey(email, gated = true)) }

    /** L2 enrol — after the biometric authenticates the cipher, encrypt + store K. */
    fun finishStoreKeyL2(email: String, k: ByteArray, authedCipher: Cipher) {
        putCt(email, authedCipher.iv, authedCipher.doFinal(k))
    }

    /** L2 unlock — a DECRYPT cipher on the gated key (with the stored IV), to authenticate in a prompt;
     *  null if absent/unusable. */
    fun decryptCipherL2(email: String): Cipher? {
        val (iv, _) = readCt(email) ?: return null
        val key = existingAesKey(email, gated = true) ?: return null
        return runCatching {
            Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        }.getOrNull()
    }

    /** L2 unlock — after the biometric authenticates the cipher, decrypt + return K. */
    fun finishReadKeyL2(email: String, authedCipher: Cipher): ByteArray {
        val (_, ct) = readCt(email) ?: error("no K ciphertext")
        return authedCipher.doFinal(ct)
    }

    private fun ensureAesKey(email: String, gated: Boolean): SecretKey {
        existingAesKey(email, gated)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val b = KeyGenParameterSpec.Builder(aesAlias(email, gated), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (gated) {
            b.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                b.setUserAuthenticationValidityDurationSeconds(-1) // every use requires auth
            }
        }
        gen.init(b.build())
        return gen.generateKey()
    }

    private fun existingAesKey(email: String, gated: Boolean): SecretKey? {
        val ks = keystore()
        val alias = aesAlias(email, gated)
        return if (ks.containsAlias(alias)) ks.getKey(alias, null) as SecretKey else null
    }

    private fun putCt(email: String, iv: ByteArray, ct: ByteArray) {
        prefs.edit().putString(ctKey(email), "${b64(iv)}:${b64(ct)}").apply()
    }

    private fun readCt(email: String): Pair<ByteArray, ByteArray>? {
        val parts = (prefs.getString(ctKey(email), null) ?: return null).split(":")
        if (parts.size != 2) return null
        return unb64(parts[0]) to unb64(parts[1])
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
    private fun aesAlias(email: String, gated: Boolean) = if (gated) "veyq.k-aes.$email" else "veyq.k-aes-ungated.$email"
    private fun ctKey(email: String) = "kCt.$email"

    // MARK: - Keystore HMAC (L1)

    private fun ensureKey(email: String) {
        if (keystore().containsAlias(alias(email))) return
        try {
            generate(email, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(email, strongBox = false)
        }
    }

    private fun generate(email: String, strongBox: Boolean) {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias(email), KeyProperties.PURPOSE_SIGN)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        gen.init(spec)
        gen.generateKey()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private fun alias(email: String) = "veyq.device-hmac.$email"
    private fun levelKey(email: String) = "level.$email"
    private fun wrapKey(email: String) = "wrapId.$email"
    private fun tokenKey(email: String) = "refreshToken.$email"

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val PREFS = "veyq.device-security"
        const val DEVICE_BIND = "veyq/v1/device-bind"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
