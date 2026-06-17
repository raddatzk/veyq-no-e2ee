package app.veyq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface),
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Veyq.Text, fontSize = 24.sp) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            val ctx = androidx.compose.ui.platform.LocalContext.current
            var unlockOn by remember { mutableStateOf(DeviceUnlock.isEnabled(ctx)) }
            if (unlockOn) {
                Section(L10n.t("settings.deviceUnlock"))
                Button(
                    onClick = { DeviceUnlock.forget(ctx); unlockOn = false },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp),
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
                shape = RoundedCornerShape(10.dp),
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
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Veyq.SurfaceMuted, contentColor = Veyq.Danger),
            ) { Text(L10n.t("settings.disconnect"), fontWeight = FontWeight.SemiBold) }
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
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
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
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Veyq.Text, fontSize = 15.sp)
        Text(value, color = Veyq.Muted, fontSize = 15.sp)
    }
}
