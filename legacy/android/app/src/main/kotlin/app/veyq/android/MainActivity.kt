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
import app.veyq.android.ui.HomeRoute
import app.veyq.android.ui.HomeScreen
import app.veyq.android.ui.L10n
import app.veyq.android.ui.LoginScreen
import app.veyq.android.ui.RegisterScreen
import app.veyq.android.ui.VeyqTheme
import app.veyq.crypto.EmailChange
import app.veyq.crypto.InviteLink
import app.veyq.crypto.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Greenfield Android app entry. Slice 1: connect → login → landed, following iOS in Material 3 on top of
 * the `:core` crypto module. The real tab bar + MemoryHome arrive in slice 2.
 */
class MainActivity : FragmentActivity() {
    // Deep links the app was opened with: veyq://invite (→ register) and veyq://open (→ open a member path).
    private val deepLink = mutableStateOf<InviteLink.DeepLink?>(null)
    private val openLink = mutableStateOf<HomeRoute.DeepOpen?>(null)
    // veyq://email-verify?token=… — confirm an email change (token-only).
    private val emailVerify = mutableStateOf<String?>(null)

    /**
     * On launch, wipe the FileProvider share staging dir (cacheDir/shared). It holds only transient artifacts
     * shared out via the system share sheet — the Emergency Kit PDF (which embeds the plaintext Secret Key) and
     * media-download temp files — none of which can be mid-share at a cold start. This bounds a Secret-Key-bearing
     * PDF's lifetime to one session instead of letting it linger in cache indefinitely.
     */
    private fun sweepSharedCache() {
        runCatching { java.io.File(cacheDir, "shared").listFiles()?.forEach { it.delete() } }
    }

    private fun captureDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        EmailChange.parseDeepLink(data)?.let { emailVerify.value = it; return }
        InviteLink.parseDeepLink(data)?.let { deepLink.value = it; return }
        HomeRoute.parseDeepLink(data)?.let { openLink.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sweepSharedCache()
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
                        openLink = openLink.value,
                        onOpenLinkConsumed = { openLink.value = null },
                        emailVerify = emailVerify.value,
                        onEmailVerifyConsumed = { emailVerify.value = null },
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
    openLink: HomeRoute.DeepOpen?,
    onOpenLinkConsumed: () -> Unit,
    emailVerify: String?,
    onEmailVerifyConsumed: () -> Unit,
    onTheme: (String) -> Unit,
    onLocale: (String) -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.Connect) }
    var apiBase by remember { mutableStateOf("") }
    var appBaseUrl by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<Login.UnlockedSession?>(null) }
    var invite by remember { mutableStateOf<InviteLink.Parts?>(null) }
    var pendingOpenPath by remember { mutableStateOf<String?>(null) }
    var pendingEmailVerify by remember { mutableStateOf<String?>(null) }
    var emailVerifyResult by remember { mutableStateOf<String?>(null) }

    // A fresh invite deep link (when not already signed in) jumps straight to registration on that instance.
    androidx.compose.runtime.LaunchedEffect(deepLink) {
        if (deepLink != null && session == null) {
            apiBase = deepLink.apiBase; appBaseUrl = deepLink.apiBase; invite = deepLink.parts; phase = Phase.Register
            onDeepLinkConsumed()
        }
    }
    // A member "open in app" deep link → open that path once landed (sign in first if needed).
    androidx.compose.runtime.LaunchedEffect(openLink) {
        openLink?.let {
            pendingOpenPath = it.path
            if (session == null) { apiBase = it.apiBase; appBaseUrl = it.apiBase; phase = Phase.Login }
            onOpenLinkConsumed()
        }
    }
    // Email-change confirmation: stash the token, then confirm once an instance is known (token-only). The
    // server normally emails a web link the browser handles; this native path is for veyq://email-verify links.
    androidx.compose.runtime.LaunchedEffect(emailVerify) {
        if (emailVerify != null) { pendingEmailVerify = emailVerify; onEmailVerifyConsumed() }
    }
    androidx.compose.runtime.LaunchedEffect(pendingEmailVerify, apiBase) {
        val tok = pendingEmailVerify
        if (tok != null && apiBase.isNotEmpty()) {
            val r = runCatching { withContext(Dispatchers.IO) { EmailChange.confirm(apiBase, tok) } }
            emailVerifyResult = if (r.isSuccess) L10n.t("emailChange.confirmed") else L10n.t("emailChange.confirmFailed")
            pendingEmailVerify = null
        }
    }

    when (phase) {
        Phase.Connect -> ConnectScreen(apiBase) { base, appUrl -> apiBase = base; appBaseUrl = appUrl ?: base; phase = Phase.Login }
        Phase.Login -> LoginScreen(apiBase, onChangeInstance = { phase = Phase.Connect }) { session = it; phase = Phase.Landed }
        Phase.Register -> RegisterScreen(apiBase, invite!!, onCancel = { phase = Phase.Login }) { session = it; phase = Phase.Landed }
        Phase.Landed -> HomeScreen(apiBase, appBaseUrl, session!!, theme, onTheme, onLocale, pendingOpenPath, { pendingOpenPath = null }) { session = null; phase = Phase.Login }
    }

    emailVerifyResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { emailVerifyResult = null },
            title = { Text(L10n.t("emailChange.confirmTitle")) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { emailVerifyResult = null }) { Text(L10n.t("common.close")) } },
        )
    }
}
