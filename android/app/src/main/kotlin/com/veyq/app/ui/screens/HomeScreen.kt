package com.veyq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyq.app.ui.AppModel
import com.veyq.app.ui.components.MessageBox
import com.veyq.app.ui.components.PrimaryButton
import com.veyq.app.ui.theme.veyqColors
import kotlinx.coroutines.launch

/**
 * The unlocked state — the Android twin of Apple's `HomeView`. Login + unlock is the whole of this slice,
 * so this is intentionally a thin landing screen (content — Memory / Chapters — is a later slice). It
 * confirms the operator-blind result: a session is held and the vault is open on-device, with a sign-out
 * that revokes the session and re-locks.
 */
@Composable
fun HomeScreen(app: AppModel) {
    var busy by remember { mutableStateOf(false) }
    var untrusting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = veyqColors

    Box(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(84.dp).clip(CircleShape).background(c.success.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Unlocked", tint = c.success, modifier = Modifier.size(44.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Unlocked", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
                Text(app.email, fontSize = 15.sp, color = c.muted)
                Text(app.apiBase, fontSize = 13.sp, color = c.muted)
            }

            Box(modifier = Modifier.widthIn(max = 360.dp)) {
                MessageBox(
                    "Your keys are open on this device only. The server holds ciphertext — your content stays end-to-end encrypted.",
                    error = false,
                )
            }

            if (app.deviceTrustFailed) {
                Box(modifier = Modifier.widthIn(max = 360.dp)) {
                    MessageBox("Couldn’t trust this device — you’ll use your Secret Key next time.")
                }
            }

            if (app.deviceTrusted) {
                Column(
                    modifier = Modifier.widthIn(max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessageBox(
                        "This device is trusted — next time you’ll unlock with just your password.",
                        error = false,
                    )
                    Text(
                        if (untrusting) "…" else "Stop trusting this device",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.danger,
                        modifier = Modifier
                            .testTag("Stop trusting this device")
                            .clickable(enabled = !untrusting) {
                                untrusting = true
                                scope.launch { app.untrust(); untrusting = false }
                            },
                    )
                }
            }

            Box(modifier = Modifier.widthIn(max = 220.dp)) {
                PrimaryButton(title = "Sign out", busy = busy) {
                    busy = true
                    scope.launch {
                        app.signOut()
                        busy = false
                    }
                }
            }
        }
    }
}
