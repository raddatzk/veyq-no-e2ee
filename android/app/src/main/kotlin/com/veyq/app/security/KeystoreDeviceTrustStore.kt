package com.veyq.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.veyq.client.DeviceTrustStore
import com.veyq.client.TrustRecord
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Android Keystore-backed [DeviceTrustStore] (cr-0021, L1). The `deviceFactor` is derived from a
 * **non-extractable HMAC-SHA256 key in the AndroidKeyStore** (StrongBox where the device supports it) by
 * signing a fixed constant — the web-parity model: the key bytes never enter the app heap, so an exfiltrated
 * backup or a heap dump cannot carry the factor off-device for an offline attack. The password stays a real
 * second factor, so a stolen *locked* device opens nothing.
 *
 * Only the server `wrapId` is persisted (in private `SharedPreferences`); the `deviceFactor` is recomputed
 * from the Keystore key on demand. The Master Key, the KEK, and the Secret Key are never stored.
 */
class KeystoreDeviceTrustStore(context: Context) : DeviceTrustStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isTrusted(email: String): Boolean = prefs.getString(wrapKey(email), null) != null

    override fun load(email: String): TrustRecord? {
        val wrapId = prefs.getString(wrapKey(email), null) ?: return null
        return TrustRecord(deviceFactor(email), wrapId)
    }

    override fun beginEnrol(email: String): ByteArray {
        ensureKey(email)
        return deviceFactor(email)
    }

    override fun commitWrapId(email: String, wrapId: String) {
        prefs.edit().putString(wrapKey(email), wrapId).apply()
    }

    override fun clear(email: String) {
        prefs.edit().remove(wrapKey(email)).apply()
        runCatching { keystore().deleteEntry(alias(email)) }
    }

    // MARK: - Keystore HMAC

    private fun ensureKey(email: String) {
        val ks = keystore()
        if (ks.containsAlias(alias(email))) return
        // Prefer StrongBox; fall back if the device has no StrongBox (generateKey throws then).
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

    private fun deviceFactor(email: String): ByteArray {
        val key = keystore().getKey(alias(email), null) as SecretKey
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(DEVICE_BIND.toByteArray(Charsets.UTF_8))
        }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private fun alias(email: String) = "veyq.device-hmac.$email"
    private fun wrapKey(email: String) = "wrapId.$email"

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val PREFS = "veyq.device-trust"
        const val DEVICE_BIND = "veyq/v1/device-bind"
    }
}
