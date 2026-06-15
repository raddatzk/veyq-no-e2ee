package app.veyq.android.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * uc.account.device-unlock (Android) — "remember me" behind a biometric. Credentials are stored in
 * EncryptedSharedPreferences (a Keystore-backed master key encrypts them at rest); reading them is gated by
 * a BiometricPrompt, so unlocking requires the user's fingerprint/face. No app-side crypto beyond the
 * Keystore-backed store.
 */
object DeviceUnlock {
    data class Creds(val apiBase: String, val email: String, val password: String, val secretKey: String)

    private const val FILE = "veyq_device_unlock"
    private const val KEY = "creds"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, FILE,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Whether the device has a usable biometric enrolled. */
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(ctx: Context): Boolean = runCatching { prefs(ctx).contains(KEY) }.getOrDefault(false)

    fun save(ctx: Context, creds: Creds) {
        val json = JSONObject().put("apiBase", creds.apiBase).put("email", creds.email).put("password", creds.password).put("secretKey", creds.secretKey)
        prefs(ctx).edit().putString(KEY, json.toString()).apply()
    }

    fun forget(ctx: Context) {
        runCatching { prefs(ctx).edit().remove(KEY).apply() }
    }

    /** Prompt the biometric; on success, decrypt + return the stored credentials. */
    suspend fun load(activity: FragmentActivity): Creds? {
        if (!authenticate(activity)) return null
        val raw = runCatching { prefs(activity).getString(KEY, null) }.getOrNull() ?: return null
        val o = JSONObject(raw)
        return Creds(o.getString("apiBase"), o.getString("email"), o.getString("password"), o.getString("secretKey"))
    }

    private suspend fun authenticate(activity: FragmentActivity): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { if (cont.isActive) cont.resume(false) }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(L10n.t("login.unlock"))
                .setNegativeButtonText(L10n.t("common.cancel"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
        )
    }
}
