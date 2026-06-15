package app.veyq.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.veyq.android.ui.ConnectScreen
import app.veyq.android.ui.HomeScreen
import app.veyq.android.ui.InviteLink
import app.veyq.android.ui.L10n
import app.veyq.android.ui.LoginScreen
import app.veyq.android.ui.RegisterScreen
import app.veyq.android.ui.VeyqTheme
import app.veyq.crypto.Login

/**
 * Greenfield Android app entry. Slice 1: connect → login → landed, following iOS in Material 3 on top of
 * the `:core` crypto module. The real tab bar + MemoryHome arrive in slice 2.
 */
class MainActivity : FragmentActivity() {
    // An invite deep link (veyq://invite) the app was opened with — consumed by AuthFlow to register.
    private val deepLink = mutableStateOf<InviteLink.DeepLink?>(null)

    private fun captureDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        InviteLink.parseDeepLink(data)?.let { deepLink.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDeepLink(intent)
        val prefs = getSharedPreferences("veyq", Context.MODE_PRIVATE)
        L10n.locale = prefs.getString("veyq.locale", "de") ?: "de"
        setContent {
            var theme by remember { mutableStateOf(prefs.getString("veyq.theme", "system") ?: "system") }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            VeyqTheme(dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthFlow(
                        theme,
                        deepLink = deepLink.value,
                        onDeepLinkConsumed = { deepLink.value = null },
                        onTheme = { theme = it; prefs.edit().putString("veyq.theme", it).apply() },
                        onLocale = { L10n.locale = it; prefs.edit().putString("veyq.locale", it).apply() },
                    )
                }
            }
        }
    }
}

private enum class Phase { Connect, Login, Register, Landed }

@Composable
private fun AuthFlow(
    theme: String,
    deepLink: InviteLink.DeepLink?,
    onDeepLinkConsumed: () -> Unit,
    onTheme: (String) -> Unit,
    onLocale: (String) -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.Connect) }
    var apiBase by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<Login.UnlockedSession?>(null) }
    var invite by remember { mutableStateOf<InviteLink.Parts?>(null) }

    // A fresh invite deep link (when not already signed in) jumps straight to registration on that instance.
    androidx.compose.runtime.LaunchedEffect(deepLink) {
        if (deepLink != null && session == null) {
            apiBase = deepLink.apiBase; invite = deepLink.parts; phase = Phase.Register
            onDeepLinkConsumed()
        }
    }

    when (phase) {
        Phase.Connect -> ConnectScreen(apiBase) { apiBase = it; phase = Phase.Login }
        Phase.Login -> LoginScreen(apiBase, onChangeInstance = { phase = Phase.Connect }) { session = it; phase = Phase.Landed }
        Phase.Register -> RegisterScreen(apiBase, invite!!, onCancel = { phase = Phase.Login }) { session = it; phase = Phase.Landed }
        Phase.Landed -> HomeScreen(apiBase, session!!, theme, onTheme, onLocale) { session = null; phase = Phase.Login }
    }
}
