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
import com.veyq.app.ui.AppModel
import com.veyq.app.ui.components.AuthCard
import com.veyq.app.ui.components.KitImportBar
import com.veyq.app.ui.components.MessageBox
import com.veyq.app.ui.components.PrimaryButton
import com.veyq.app.ui.components.VeyqField
import com.veyq.app.ui.components.VeyqKeyboard
import com.veyq.app.ui.theme.veyqColors
import com.veyq.client.KitData
import com.veyq.client.importKitFromBytes
import kotlinx.coroutines.launch

/**
 * Step 1 of sign-in: **email + password → `login()`** — the Android twin of Apple's `SignInView`. Mirrors
 * the web two-step flow; the Secret-Key floor ([UnlockScreen]) is the second step. Operator-blind: the
 * password stays in this screen's transient state, is used on-device only (the data layer signs a nonce
 * with a password-derived key — the password itself is never sent), and is dropped after submit.
 * (cr-0018 phase 2 adds the in-screen Emergency-Kit import: fill email + park the Secret Key.)
 */
@Composable
fun SignInScreen(app: AppModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Apply an imported kit: fill the email + park the Secret Key for the floor. On-device only.
    fun apply(kit: KitData) {
        error = ""
        email = kit.email
        app.pendingSecretKey = kit.secretKey
        info = "Emergency Kit imported — your Secret Key is ready for unlock."
    }

    val uploadKit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val kit = bytes?.let { importKitFromBytes(it) }
        if (kit == null) error = "Couldn't read an Emergency Kit from that file." else apply(kit)
    }

    val canSubmit = email.trim().isNotEmpty() && password.isNotEmpty()

    AuthCard(title = "Sign in", subtitle = app.apiBase) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VeyqField(
                label = "Email",
                value = email,
                onValueChange = { email = it },
                placeholder = "you@example.com",
                keyboard = VeyqKeyboard.EMAIL,
            )
            VeyqField(label = "Password", value = password, onValueChange = { password = it }, secure = true)
            KitImportBar(
                label = "Import your Emergency Kit (fills email + Secret Key)",
                onUpload = { uploadKit.launch(arrayOf("application/pdf", "text/*")) },
            )
            PrimaryButton(title = "Sign in", busy = busy, enabled = canSubmit) {
                busy = true
                error = ""
                val e = email.trim()
                val p = password
                scope.launch {
                    val err = app.login(e, p)
                    if (err == null) {
                        // Drop the password from screen state; the model carries it to the unlock step.
                        password = ""
                    } else {
                        error = err
                    }
                    busy = false
                }
            }
            Text(
                "Use a different instance",
                fontSize = 13.sp,
                color = veyqColors.muted,
                modifier = Modifier
                    .testTag("Use a different instance")
                    .clickable { app.changeInstance() },
            )
            if (info.isNotEmpty()) MessageBox(info, error = false)
            if (error.isNotEmpty()) MessageBox(error)
        }
    }
}
