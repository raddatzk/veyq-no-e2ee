package com.veyq.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.veyq.client.VeyqClient
import com.veyq.client.VeyqError
import com.veyq.client.VeyqInstance
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives the connect → sign-in → home flow and owns the [VeyqClient] once an instance is chosen — the
 * Android twin of Apple's `AppModel`. Compose-state-backed so [com.veyq.app.ui.RootScreen] recomposes on
 * a phase change.
 *
 * Auth ≠ unlock: a successful sign-in does both a `login` (session) and an `unlock` (vault) — the two
 * distinct data-layer steps — behind one submit. The password + Secret Key are held only transiently in
 * the sign-in screen and never reach this model. The blocking `:client` calls run on [Dispatchers.IO];
 * state mutations happen back on the caller's (main) context.
 */
class AppModel : ViewModel() {
    enum class Phase { CONNECT, SIGN_IN, HOME }

    var phase by mutableStateOf(Phase.CONNECT)
        private set

    /** The chosen instance base URL (shown as the sign-in subtitle / on home). */
    var apiBase by mutableStateOf("")
        private set

    /** The signed-in account email (shown on the home screen). */
    var email by mutableStateOf("")
        private set

    private var client: VeyqClient? = null

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
        val client = VeyqClient(VeyqInstance(baseUrl))
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
     * Run the two data-layer steps — `login` then `unlock` — behind one submit. Returns `null` on success
     * (the flow advances to home) or a user-facing error string. Operator-blind: the password + Secret Key
     * are used on-device only and dropped by the caller after this returns.
     */
    suspend fun signInAndUnlock(email: String, password: String, secretKey: String): String? {
        val client = this.client ?: return "Not connected to an instance."
        return try {
            withContext(Dispatchers.IO) {
                client.login(email = email, password = password)
                client.unlock(password = password, secretKey = secretKey)
            }
            this.email = email
            phase = Phase.HOME
            null
        } catch (e: VeyqError.UnlockUnavailable) {
            "This account has no password unlock configured."
        } catch (e: Exception) {
            // Wrong password / Secret Key / unknown account all surface as one generic failure
            // (non-enumerable, operator-blind).
            "Sign-in failed. Check your email, password, and Secret Key."
        }
    }

    /** Sign out: revoke the session, re-lock the vault, and return to sign-in (same instance). */
    suspend fun signOut() {
        withContext(Dispatchers.IO) { client?.logout() }
        email = ""
        phase = Phase.SIGN_IN
    }

    /** Pick a different instance — drop the client and return to connect. */
    fun changeInstance() {
        client = null
        apiBase = ""
        email = ""
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
