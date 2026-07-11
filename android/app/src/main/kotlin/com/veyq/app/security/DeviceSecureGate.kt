package com.veyq.app.security

import android.app.KeyguardManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Whether the device has a secure lock screen — a PIN, pattern, password or enrolled biometric
 * ([KeyguardManager.isDeviceSecure]). The precondition for the passwordless "No Login" level (L3): that level
 * stores the random key `K` in the Keystore **ungated** (only `setUnlockedDeviceRequired`), so on a device
 * with no device credential that barrier is vacuous ("unlocked" is always true) and `K` — hence the Master Key
 * — would be recoverable with no authentication. The Android twin of Apple's
 * `LAContext().canEvaluatePolicy(.deviceOwnerAuthentication)` check.
 */
object DeviceSecureGate {
    fun isSecure(context: Context): Boolean =
        context.getSystemService<KeyguardManager>()?.isDeviceSecure == true
}
