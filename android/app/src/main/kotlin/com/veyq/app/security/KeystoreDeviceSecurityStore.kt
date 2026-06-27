package com.veyq.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.veyq.client.DeviceLevel
import com.veyq.client.DeviceSecurityStore
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

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
        prefs.edit().remove(levelKey(email)).remove(wrapKey(email)).remove(tokenKey(email)).apply()
        runCatching { keystore().deleteEntry(alias(email)) }
    }

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
    }
}
