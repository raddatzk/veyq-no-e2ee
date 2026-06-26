package com.veyq.app.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyq.app.ui.AppModel
import com.veyq.app.ui.components.AuthCard
import com.veyq.app.ui.components.MessageBox
import com.veyq.app.ui.components.PrimaryButton
import com.veyq.app.ui.components.VeyqField
import com.veyq.app.ui.theme.veyqColors
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
    val scope = rememberCoroutineScope()

    AuthCard(title = "Unlock your vault", subtitle = app.email) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VeyqField(
                label = "Secret Key",
                value = secretKey,
                onValueChange = { secretKey = it },
                secure = true,
                hint = "From your Emergency Kit. Used with your password to unlock — never sent.",
            )
            PrimaryButton(title = "Unlock", busy = busy, enabled = secretKey.isNotEmpty()) {
                busy = true
                error = ""
                val s = secretKey
                scope.launch {
                    val err = app.unlock(s)
                    if (err == null) secretKey = "" else error = err
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
            if (error.isNotEmpty()) MessageBox(error)
        }
    }
}
