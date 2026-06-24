package app.veyq.android.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * uc.account.recover-keys (Android) — "remember me" behind a biometric, crypto-bound (not a soft UI gate).
 *
 * The credentials (password + Secret Key) are sealed under a random AES-GCM key, which is itself RSA-wrapped to
 * an AndroidKeyStore keypair whose PRIVATE key is `setUserAuthenticationRequired(true)`. Saving uses the public
 * key (no prompt — the user just logged in). UNLOCK requires a fresh strong biometric: the RSA-decrypt is run
 * inside a BiometricPrompt.CryptoObject, so the wrapped AES key (and thus the credentials) is undecryptable
 * without the user actually authenticating to the TEE — a bypass that reaches the prefs read no longer suffices.
 * `setInvalidatedByBiometricEnrollment` drops the key if a new fingerprint is enrolled (the user re-logs in).
 */
object DeviceUnlock {
    data class Creds(val apiBase: String, val email: String, val password: String, val secretKey: String, val deviceRef: String? = null)

    private const val PREFS = "veyq_device_unlock"
    private const val KEY_ALIAS = "veyq_device_unlock_rsa"
    private const val P_WRAPPED = "wrappedKey"
    private const val P_IV = "iv"
    private const val P_CT = "ct"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    // AndroidKeyStore RSA-OAEP always uses MGF1-SHA1 internally; pin both encrypt + decrypt to it so they agree.
    private fun oaep() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    /** Whether the device has a usable strong biometric enrolled. */
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(ctx: Context): Boolean = runCatching { prefs(ctx).contains(P_WRAPPED) }.getOrDefault(false)

    private fun ensureKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(spec); generateKeyPair()
        }
    }

    /** Seal the credentials. Public-key wrap → no biometric prompt here (the user just authenticated to log in). */
    fun save(ctx: Context, creds: Creds) {
        ensureKey()
        val json = JSONObject().put("apiBase", creds.apiBase).put("email", creds.email)
            .put("password", creds.password).put("secretKey", creds.secretKey)
        if (!creds.deviceRef.isNullOrBlank()) json.put("deviceRef", creds.deviceRef)
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv)); doFinal(payload)
        }
        val pub = keyStore().getCertificate(KEY_ALIAS).publicKey
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, pub, oaep()); doFinal(aesKey)
        }
        prefs(ctx).edit().putString(P_WRAPPED, b64(wrapped)).putString(P_IV, b64(iv)).putString(P_CT, b64(ct)).apply()
    }

    fun forget(ctx: Context) {
        runCatching { prefs(ctx).edit().clear().apply() }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** Prompt a strong biometric; on success the bound cipher unwraps the AES key → decrypt the credentials. */
    suspend fun load(activity: FragmentActivity): Creds? {
        val p = prefs(activity)
        val wrapped = p.getString(P_WRAPPED, null)?.let(::unb64) ?: return null
        val iv = p.getString(P_IV, null)?.let(::unb64) ?: return null
        val ct = p.getString(P_CT, null)?.let(::unb64) ?: return null
        val rsa = try {
            Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
                init(Cipher.DECRYPT_MODE, keyStore().getKey(KEY_ALIAS, null) as PrivateKey, oaep())
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            forget(activity); return null // a new biometric was enrolled — the key is gone; re-login required
        } catch (e: Exception) {
            return null
        }
        val authed = authenticate(activity, BiometricPrompt.CryptoObject(rsa)) ?: return null
        return runCatching {
            val aesKey = authed.cipher!!.doFinal(wrapped)
            val json = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv)); doFinal(ct)
            }
            val o = JSONObject(String(json, Charsets.UTF_8))
            Creds(o.getString("apiBase"), o.getString("email"), o.getString("password"), o.getString("secretKey"), o.optString("deviceRef").ifBlank { null })
        }.getOrNull()
    }

    private suspend fun authenticate(activity: FragmentActivity, crypto: BiometricPrompt.CryptoObject): BiometricPrompt.CryptoObject? =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(result.cryptoObject) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { if (cont.isActive) cont.resume(null) }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(L10n.t("login.unlock"))
                    .setNegativeButtonText(L10n.t("common.cancel"))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
                crypto,
            )
        }
}
