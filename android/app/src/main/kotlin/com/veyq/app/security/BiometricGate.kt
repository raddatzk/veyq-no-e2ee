package com.veyq.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Wraps Android's async, Activity-bound [BiometricPrompt] as a **suspend** function so the orchestration
 * coroutines can `await` a fingerprint/face (cr-0022 L2). The [Cipher] is bound via a [CryptoObject] —
 * `BiometricPrompt` only unlocks the Keystore key (and so the cipher) after a `BIOMETRIC_STRONG` match, so
 * the random key `K` it encrypts/decrypts is released only on a live biometric.
 */
object BiometricGate {

    /** Whether a strong biometric is enrolled and usable on this device (drives the L2 chooser row). */
    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Show the biometric prompt for [cipher]; resume with the **authenticated** cipher on success, or null
     * on cancel / error / no match. Must be called on the main thread (BiometricPrompt requirement).
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
    ): Cipher? = suspendCancellableCoroutine { cont ->
        val executor: Executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onAuthenticationFailed() {
                // a single non-match — the prompt stays up; nothing to resume yet.
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use Secret Key instead")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
