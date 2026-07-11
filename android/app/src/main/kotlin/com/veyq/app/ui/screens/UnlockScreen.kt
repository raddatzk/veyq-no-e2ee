package com.veyq.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.veyq.app.i18n.LocalL10n
import com.veyq.app.security.BiometricGate
import com.veyq.app.security.DeviceSecureGate
import com.veyq.app.ui.AppModel
import com.veyq.app.ui.components.AuthCard
import com.veyq.app.ui.components.DeviceSecurityChooser
import com.veyq.app.ui.components.DeviceUnlockLevel
import com.veyq.app.ui.components.KitImportBar
import com.veyq.app.ui.components.KitScanner
import com.veyq.app.ui.components.MessageBox
import com.veyq.app.ui.components.PrimaryButton
import com.veyq.app.ui.components.VeyqField
import com.veyq.app.ui.theme.veyqColors
import com.veyq.client.importKitFromBytes
import kotlinx.coroutines.launch

/**
 * Step 2 of sign-in — the **Secret-Key floor**: the carried password (from login) + the Secret Key →
 * `unlock()` (derive the Unlock KEK on-device, open the Master Key + identity into the in-memory vault).
 * The Android twin of Apple's `UnlockView`. Operator-blind: the Secret Key stays in this screen's transient
 * state, is used on-device only, and is dropped after submit; nothing is sent.
 *
 * The field pre-fills from `app.pendingSecretKey` when a **login-screen kit import** parked one (cr-0018
 * phase 2); the floor's own upload/scan affordances are added in phases 2–3.
 */
@Composable
fun UnlockScreen(app: AppModel) {
    // Consume a Secret Key parked by a login-screen kit import (empty otherwise).
    var secretKey by remember { mutableStateOf(app.pendingSecretKey) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    // The device unlock level the user picks on this floor (no default — they must choose; ADR 0005 §10).
    var level by remember { mutableStateOf<DeviceUnlockLevel?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricAvailable = activity != null && BiometricGate.available(activity)
    val deviceSecure = DeviceSecureGate.isSecure(context)
    val l = LocalL10n.current

    val uploadKit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val kit = bytes?.let { importKitFromBytes(it) }
        if (kit == null) error = l.t("kit.readError") else { secretKey = kit.secretKey; error = "" }
    }

    if (scanning) {
        KitScanner(
            onResult = { kit -> scanning = false; secretKey = kit.secretKey; error = "" },
            onCancel = { scanning = false },
        )
        return
    }

    AuthCard(title = l.t("unlock.title"), subtitle = app.email) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VeyqField(
                label = l.t("unlock.secretKey"),
                value = secretKey,
                onValueChange = { secretKey = it },
                secure = true,
                hint = l.t("unlock.secretKeyHint"),
            )
            KitImportBar(
                label = l.t("unlock.kitImport"),
                onUpload = { uploadKit.launch(arrayOf("application/pdf", "text/*")) },
                onScan = { scanning = true },
            )
            DeviceSecurityChooser(
                selection = level,
                onSelect = { level = it },
                biometricAvailable = biometricAvailable,
                deviceSecure = deviceSecure,
            )
            PrimaryButton(title = l.t("unlock.action"), busy = busy, enabled = secretKey.isNotEmpty() && level != null) {
                val chosen = level ?: return@PrimaryButton
                busy = true
                error = ""
                val s = secretKey
                scope.launch {
                    val err = app.unlock(s, chosen, activity)
                    if (err == null) secretKey = "" else error = err
                    busy = false
                }
            }
            Text(
                l.t("common.differentInstance"),
                fontSize = 13.sp,
                color = veyqColors.muted,
                modifier = Modifier
                    .testTag("Use a different instance")
                    .clickable { app.changeInstance() },
            )
            if (error.isNotEmpty()) MessageBox(error)
        }
    }
}
