package com.veyq.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import com.veyq.app.security.BiometricGate
import com.veyq.app.security.KeystoreDeviceSecurityStore
import com.veyq.app.ui.components.DeviceUnlockLevel
import com.veyq.client.DeviceLevel
import com.veyq.client.VeyqClient
import com.veyq.client.VeyqError
import com.veyq.client.VeyqInstance
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives the connect → sign-in → unlock → home flow and owns the [VeyqClient] once an instance is chosen —
 * the Android twin of Apple's `AppModel`. Compose-state-backed so [com.veyq.app.ui.RootScreen] recomposes
 * on a phase change.
 *
 * Auth ≠ unlock: sign-in is **two steps**, mirroring the web — `login` (email + password → session) on the
 * sign-in screen, then `unlock` (password + Secret Key → vault) on the Secret-Key floor. The password is
 * carried transiently here between the steps (never persisted, cleared on unlock / sign-out).
 * [pendingSecretKey] parks a Secret Key handed over by a **login-screen kit import** until the floor
 * consumes it. The blocking `:client` calls run on [Dispatchers.IO]; state mutations happen back on main.
 */
class AppModel(application: Application) : AndroidViewModel(application) {
    enum class Phase { LAUNCHING, CONNECT, SIGN_IN, UNLOCK, HOME }

    /** The Android Keystore-backed device-security store, injected into the [VeyqClient] (cr-0021/cr-0022). */
    private val deviceSecurity = KeystoreDeviceSecurityStore(application)
    private val launchPrefs = LaunchPrefs(application)

    var phase by mutableStateOf(Phase.LAUNCHING)
        private set

    /** Whether this device is at the "only password" level (a DEVICE wrap is enrolled) — drives the
     *  untrust control on home. */
    var deviceTrusted by mutableStateOf(false)
        private set

    /** Set when enrolling device trust failed after a floor unlock — the unlock still succeeded. */
    var deviceTrustFailed by mutableStateOf(false)

    /** The chosen instance base URL (shown as the sign-in / floor subtitle). */
    var apiBase by mutableStateOf("")
        private set

    /** The signed-in account email (shown on the floor + home). */
    var email by mutableStateOf("")
        private set

    /** A Secret Key parked by a login-screen kit import, consumed (and cleared) by the unlock floor. */
    var pendingSecretKey by mutableStateOf("")

    private var client: VeyqClient? = null

    /** The password from the login step, carried to the unlock step. Transient — never persisted. */
    private var carriedPassword: String = ""

    /** Outcome of a connect attempt. */
    sealed interface ConnectResult {
        object Connected : ConnectResult
        object SetupNeeded : ConnectResult
        data class Failed(val message: String) : ConnectResult
    }

    /**
     * Resolve + reach an instance. Native never bootstraps (setup is browser-only), so a still-unset-up
     * instance returns [ConnectResult.SetupNeeded] rather than advancing. On success the client is kept and
     * the flow moves to sign-in.
     */
    suspend fun connect(rawUrl: String): ConnectResult {
        val baseUrl = normalizeBaseUrl(rawUrl)
            ?: return ConnectResult.Failed("That doesn't look like a valid address.")
        val client = VeyqClient(VeyqInstance(baseUrl), deviceSecurity = deviceSecurity)
        return try {
            val setupNeeded = withContext(Dispatchers.IO) { client.setupRequired() }
            if (setupNeeded) {
                ConnectResult.SetupNeeded
            } else {
                this.client = client
                apiBase = baseUrl
                phase = Phase.SIGN_IN
                ConnectResult.Connected
            }
        } catch (e: Exception) {
            ConnectResult.Failed("Couldn't reach that instance. Check the address and try again.")
        }
    }

    /**
     * Step 1 — log in (email + password → session). Returns `null` on success (the flow advances to the
     * Secret-Key floor) or a user-facing error string. Operator-blind: the password is used on-device only.
     */
    suspend fun login(email: String, password: String): String? {
        val client = this.client ?: return "Not connected to an instance."
        return try {
            withContext(Dispatchers.IO) { client.login(email = email, password = password) }
            this.email = email
            deviceTrustFailed = false
            // Login-fold (cr-0008 parity): on a trusted device the DEVICE unlock runs here so the floor
            // never shows. The login just proved this password, so a failure means a corrupt/foreign/missing
            // wrap → fall through to the floor.
            if (withContext(Dispatchers.IO) { client.isTrusted(email) }) {
                val opened = runCatching { withContext(Dispatchers.IO) { client.unlockWithDevice(email, password) } }.getOrDefault(false)
                if (opened) {
                    carriedPassword = ""
                    pendingSecretKey = ""
                    deviceTrusted = true
                    phase = Phase.HOME
                    return null
                }
            }
            this.carriedPassword = password
            phase = Phase.UNLOCK
            null
        } catch (e: Exception) {
            // Wrong password / unknown account both surface as one generic failure (non-enumerable).
            "Sign-in failed. Check your email and password."
        }
    }

    /**
     * Step 2 — unlock the floor (carried password + Secret Key → vault), then apply the chosen device
     * [DeviceUnlockLevel] right after a successful unlock (reusing the known-correct password): L0 stores
     * nothing; L1 enrols the DEVICE wrap. An enrol failure does not fail the unlock — it surfaces
     * [deviceTrustFailed]. Returns `null` on success or a user-facing error string.
     */
    suspend fun unlock(secretKey: String, level: DeviceUnlockLevel, activity: FragmentActivity?): String? {
        val client = this.client ?: return "Not connected to an instance."
        return try {
            withContext(Dispatchers.IO) { client.unlock(password = carriedPassword, secretKey = secretKey) }
            runCatching { applyLevel(client, level, activity) }.onFailure { deviceTrustFailed = true }
            carriedPassword = ""
            pendingSecretKey = ""
            phase = Phase.HOME
            null
        } catch (e: VeyqError.UnlockUnavailable) {
            "This account has no password unlock configured."
        } catch (e: Exception) {
            // Wrong password / Secret Key both surface as one generic failure (non-enumerable).
            "Unlock failed. Check your Secret Key."
        }
    }

    /** Enrol the chosen device level right after a floor unlock (reusing the in-hand password / live
     *  session). L0 stores nothing; L1 = DEVICE wrap; L2 = random-K wrap, K biometric-gated (a fingerprint
     *  encrypts K); L3 = random-K wrap, K ungated. Throws on failure → the caller surfaces `deviceTrustFailed`. */
    private suspend fun applyLevel(client: VeyqClient, level: DeviceUnlockLevel, activity: FragmentActivity?) {
        when (level) {
            DeviceUnlockLevel.SECRET_KEY_EACH_TIME -> Unit
            DeviceUnlockLevel.ONLY_PASSWORD -> {
                withContext(Dispatchers.IO) { client.trustThisDevice(email, carriedPassword) }
                deviceTrusted = true
            }
            DeviceUnlockLevel.NO_LOGIN -> {
                val k = randomKey()
                withContext(Dispatchers.IO) { deviceSecurity.storeKeyUngated(email, k) }
                withContext(Dispatchers.IO) { client.setPasswordlessLevel(email, DeviceLevel.NO_LOGIN, k) }
                launchPrefs.remember(apiBase, email)
                deviceTrusted = true
            }
            DeviceUnlockLevel.BIOMETRIC -> {
                val act = activity ?: error("no activity for the biometric prompt")
                val k = randomKey()
                val cipher = deviceSecurity.encryptCipherL2(email)
                val authed = BiometricGate.authenticate(act, cipher, "Set up biometric unlock", email)
                    ?: error("biometric cancelled")
                deviceSecurity.finishStoreKeyL2(email, k, authed)
                withContext(Dispatchers.IO) { client.setPasswordlessLevel(email, DeviceLevel.BIOMETRIC, k) }
                launchPrefs.remember(apiBase, email)
                deviceTrusted = true
            }
        }
    }

    /**
     * App-launch auto-restore for a passwordless device (cr-0022 L2/L3): if this device last used L2/L3,
     * resume the persisted session and open the vault — silently for L3, after a biometric for L2. Anything
     * missing / failed falls back to connect / sign-in. Called once at launch.
     */
    suspend fun bootstrap(activity: FragmentActivity?) {
        if (phase != Phase.LAUNCHING) return
        val inst = launchPrefs.instance
        val acct = launchPrefs.account
        val baseUrl = inst?.let { normalizeBaseUrl(it) }
        if (baseUrl == null || acct == null) { phase = Phase.CONNECT; return }

        val client = VeyqClient(VeyqInstance(baseUrl), deviceSecurity = deviceSecurity)
        val level = withContext(Dispatchers.IO) { client.currentLevel(acct) }
        if (level != DeviceLevel.BIOMETRIC && level != DeviceLevel.NO_LOGIN) { phase = Phase.CONNECT; return }
        this.client = client
        this.apiBase = baseUrl
        this.email = acct
        this.deviceTrusted = true

        try {
            if (!withContext(Dispatchers.IO) { client.resumeSession(acct) }) { launchPrefs.clear(); phase = Phase.CONNECT; return }
            val k: ByteArray? = when (level) {
                DeviceLevel.NO_LOGIN -> withContext(Dispatchers.IO) { deviceSecurity.readKeyUngated(acct) }
                DeviceLevel.BIOMETRIC -> {
                    val act = activity ?: return  // re-run when the activity is available
                    val cipher = deviceSecurity.decryptCipherL2(acct) ?: return run { phase = Phase.SIGN_IN }
                    val authed = BiometricGate.authenticate(act, cipher, "Unlock veyq", acct) ?: return run { phase = Phase.SIGN_IN }
                    deviceSecurity.finishReadKeyL2(acct, authed)
                }
                else -> null
            }
            if (k != null && withContext(Dispatchers.IO) { client.unlockWithKey(acct, k) }) phase = Phase.HOME
            else phase = Phase.SIGN_IN
        } catch (e: Exception) {
            phase = Phase.SIGN_IN
        }
    }

    /** Drop this device's convenience level back to "Secret Key each time": remove the wrap + key + any
     *  persisted session, and forget it for auto-restore. */
    suspend fun untrust() {
        val client = this.client ?: return
        withContext(Dispatchers.IO) { runCatching { client.untrustThisDevice(email) } }
        launchPrefs.clear()
        deviceTrusted = false
    }

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Sign out: revoke the session, re-lock the vault, drop all transient secrets, and return to sign-in.
     *  Device trust (the server wrap + Keystore key) is NOT touched — that is what makes the next login fold. */
    suspend fun signOut() {
        withContext(Dispatchers.IO) { client?.logout() }
        email = ""
        carriedPassword = ""
        pendingSecretKey = ""
        deviceTrusted = false
        deviceTrustFailed = false
        phase = Phase.SIGN_IN
    }

    /** Pick a different instance — drop the client + secrets and return to connect. */
    fun changeInstance() {
        client = null
        apiBase = ""
        email = ""
        carriedPassword = ""
        pendingSecretKey = ""
        deviceTrusted = false
        deviceTrustFailed = false
        phase = Phase.CONNECT
    }
}

/**
 * Normalize a user-typed instance address into an absolute base URL string: default to https, trim a
 * trailing slash, reject anything that isn't a usable http(s) URL. Mirrors Apple's `normalizeBaseURL`.
 */
fun normalizeBaseUrl(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    while (s.endsWith("/")) s = s.dropLast(1)
    return try {
        val uri = URI(s)
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()) s else null
    } catch (e: Exception) {
        null
    }
}
