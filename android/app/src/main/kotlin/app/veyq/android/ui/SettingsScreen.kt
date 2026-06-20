package app.veyq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.veyq.crypto.EmailChange
import app.veyq.crypto.Instance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Login
import app.veyq.crypto.RotateIdentity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(apiBase: String, session: Login.UnlockedSession, theme: String, onTheme: (String) -> Unit, onLocale: (String) -> Unit, onBack: () -> Unit, onSignOut: () -> Unit) {
    Scaffold(
        containerColor = Veyq.Bg,
        topBar = {
            TopAppBar(
                title = { Text(L10n.t("settings.title"), color = Veyq.Text, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface.copy(alpha = 0.94f)),
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Veyq.Text, fontSize = 24.sp) } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Veyq.screenBackground())) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Dismissible support banner — operator opt-in via /api/setup supportUrl; re-shows per server version.
                val ctxSupport = androidx.compose.ui.platform.LocalContext.current
                val sPrefs = remember { ctxSupport.getSharedPreferences("veyq", android.content.Context.MODE_PRIVATE) }
                var supportUrl by remember { mutableStateOf<String?>(null) }
                var serverVersion by remember { mutableStateOf<String?>(null) }
                var supportDismissed by remember { mutableStateOf(sPrefs.getString("veyq.support.dismissedVersion", "") ?: "") }
                LaunchedEffect(apiBase) {
                    runCatching { withContext(Dispatchers.IO) { Instance.getSetupStatus(apiBase) } }.onSuccess {
                        supportUrl = it.supportUrl; serverVersion = it.serverVersion
                    }
                }
                if (supportUrl != null && supportDismissed != (serverVersion ?: "unversioned")) {
                    SupportBanner(url = supportUrl!!) {
                        val v = serverVersion ?: "unversioned"
                        sPrefs.edit().putString("veyq.support.dismissedVersion", v).apply()
                        supportDismissed = v
                    }
                }

                Section(L10n.t("settings.langAppearance"))
                SelectorRow(L10n.t("settings.appearance"), theme, listOf("system" to L10n.t("settings.system"), "light" to L10n.t("settings.light"), "dark" to L10n.t("settings.dark")), onTheme)
                SelectorRow(L10n.t("settings.language"), L10n.locale, listOf("de" to "Deutsch", "en" to "English"), onLocale)

                Section(L10n.t("settings.privacy"))
                val privacyCtx = androidx.compose.ui.platform.LocalContext.current
                val mapPrefs = remember { privacyCtx.getSharedPreferences("veyq", android.content.Context.MODE_PRIVATE) }
                var mapTiles by remember { mutableStateOf(mapPrefs.getBoolean("veyq.privacy.mapTiles", false)) }
                SelectorRow(L10n.t("settings.mapTiles"), if (mapTiles) "on" else "off", listOf("on" to L10n.t("settings.on"), "off" to L10n.t("settings.off"))) { sel ->
                    mapTiles = sel == "on"
                    mapPrefs.edit().putBoolean("veyq.privacy.mapTiles", mapTiles).apply()
                }

                Section(L10n.t("settings.account"))
                SettingRow(L10n.t("common.email"), session.email)

                // Email change: request a token to the NEW address (committed via the emailed link). Pure
                // identifier swap — email is not part of the KEK, so no re-wrap.
                val emailScope = rememberCoroutineScope()
                var newEmail by remember { mutableStateOf("") }
                var emailBusy by remember { mutableStateOf(false) }
                var emailMsg by remember { mutableStateOf<String?>(null) }
                var emailErr by remember { mutableStateOf<String?>(null) }
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    label = { Text(L10n.t("emailChange.newEmail")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val target = newEmail.trim()
                        if (target.isEmpty() || emailBusy) return@Button
                        emailBusy = true; emailErr = null; emailMsg = null
                        emailScope.launch {
                            val r = runCatching { withContext(Dispatchers.IO) { EmailChange.requestChange(apiBase, session.sessionToken, target) } }
                            r.onSuccess { emailMsg = L10n.t("emailChange.sent"); newEmail = "" }
                                .onFailure { emailErr = if (it is EmailChange.EmailTakenException) L10n.t("emailChange.taken") else L10n.t("emailChange.failed") }
                            emailBusy = false
                        }
                    },
                    enabled = !emailBusy && newEmail.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(Veyq.ControlRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = Veyq.SurfaceMuted, contentColor = Veyq.Accent),
                ) { Text(if (emailBusy) L10n.t("emailChange.sending") else L10n.t("emailChange.change"), fontWeight = FontWeight.SemiBold) }
                emailMsg?.let { Text(it, color = Veyq.Muted, fontSize = 13.sp) }
                emailErr?.let { Text(it, color = Veyq.Danger, fontSize = 13.sp) }

                val ctx = androidx.compose.ui.platform.LocalContext.current
                var unlockOn by remember { mutableStateOf(DeviceUnlock.isEnabled(ctx)) }
                if (unlockOn) {
                    Section(L10n.t("settings.deviceUnlock"))
                    Button(
                        onClick = { DeviceUnlock.forget(ctx); unlockOn = false },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(Veyq.ControlRadius),
                        colors = ButtonDefaults.buttonColors(containerColor = Veyq.SurfaceMuted, contentColor = Veyq.Danger),
                    ) { Text(L10n.t("settings.forgetDevice"), fontWeight = FontWeight.SemiBold) }
                }

                // Identity rotation: re-keys every grant to a fresh identity (auth/master key unchanged). The
                // in-memory session goes stale, so on success we sign out — the next login re-derives the identity.
                val scope = rememberCoroutineScope()
                var rotating by remember { mutableStateOf(false) }
                var showRotateConfirm by remember { mutableStateOf(false) }
                var rotateError by remember { mutableStateOf<String?>(null) }
                Section(L10n.t("settings.security"))
                Text(L10n.t("settings.rotateIdentityHint"), color = Veyq.Muted, fontSize = 13.sp)
                rotateError?.let { Text(it, color = Veyq.Danger, fontSize = 13.sp) }
                Button(
                    onClick = { showRotateConfirm = true },
                    enabled = !rotating,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(Veyq.ControlRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = Veyq.SurfaceMuted, contentColor = Veyq.Accent),
                ) { Text(if (rotating) L10n.t("settings.rotating") else L10n.t("settings.rotateIdentity"), fontWeight = FontWeight.SemiBold) }
                if (showRotateConfirm) {
                    AlertDialog(
                        onDismissRequest = { showRotateConfirm = false },
                        containerColor = Veyq.Surface,
                        title = { Text(L10n.t("settings.rotateIdentity"), color = Veyq.Text) },
                        text = { Text(L10n.t("settings.rotateConfirm"), color = Veyq.Muted) },
                        confirmButton = {
                            TextButton(onClick = {
                                showRotateConfirm = false
                                rotating = true
                                rotateError = null
                                scope.launch {
                                    val ok = runCatching { withContext(Dispatchers.IO) { RotateIdentity.rotateIdentity(apiBase, session) } }
                                    ok.onSuccess { onSignOut() }.onFailure { rotateError = it.message; rotating = false }
                                }
                            }) { Text(L10n.t("settings.rotateIdentity"), color = Veyq.Danger) }
                        },
                        dismissButton = { TextButton(onClick = { showRotateConfirm = false }) { Text(L10n.t("common.cancel"), color = Veyq.Muted) } },
                    )
                }

                Section(L10n.t("settings.instance"))
                SettingRow(L10n.t("settings.instance"), apiBase)

                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 12.dp),
                    shape = RoundedCornerShape(Veyq.ControlRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = Veyq.SurfaceMuted, contentColor = Veyq.Danger),
                ) { Text(L10n.t("settings.disconnect"), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Veyq.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun SelectorRow(label: String, current: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface.copy(alpha = 0.92f), RoundedCornerShape(Veyq.Radius))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Veyq.Text, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, optLabel) ->
                val active = current == key
                Text(
                    optLabel,
                    color = if (active) Color.White else Veyq.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Veyq.Accent else Color.Transparent)
                        .clickable { onSelect(key) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun SupportBanner(url: String, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface.copy(alpha = 0.92f), RoundedCornerShape(Veyq.Radius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(L10n.t("support.noticeTitle"), color = Veyq.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(L10n.t("support.noticeCopy"), color = Veyq.Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                L10n.t("support.action"),
                color = Veyq.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                },
            )
            Text(L10n.t("support.dismiss"), color = Veyq.Muted, fontSize = 13.sp, modifier = Modifier.clickable { onDismiss() })
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface.copy(alpha = 0.92f), RoundedCornerShape(Veyq.Radius))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Veyq.Text, fontSize = 15.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = Veyq.Muted, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.58f))
    }
}
