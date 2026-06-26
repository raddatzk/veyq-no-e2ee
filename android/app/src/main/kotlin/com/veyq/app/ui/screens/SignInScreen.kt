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
import com.veyq.app.ui.components.VeyqKeyboard
import com.veyq.app.ui.theme.veyqColors
import kotlinx.coroutines.launch

/**
 * Sign in with email + password + Secret Key — the Android twin of Apple's `SignInView`. One submit runs
 * the two distinct data-layer steps (`login` → session, then `unlock` → in-memory vault). Operator-blind:
 * the password and Secret Key stay in this screen's transient state, are used on-device only, and are
 * dropped after a successful submit; the password is never sent and the Secret Key never leaves the device.
 */
@Composable
fun SignInScreen(app: AppModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val canSubmit = email.trim().isNotEmpty() && password.isNotEmpty() && secretKey.isNotEmpty()

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
            VeyqField(
                label = "Secret Key",
                value = secretKey,
                onValueChange = { secretKey = it },
                secure = true,
                hint = "From your Emergency Kit. Used with your password to unlock — never sent.",
            )
            PrimaryButton(title = "Sign in & unlock", busy = busy, enabled = canSubmit) {
                busy = true
                error = ""
                val e = email.trim()
                val p = password
                val s = secretKey
                scope.launch {
                    val err = app.signInAndUnlock(e, p, s)
                    if (err == null) {
                        // Drop the secrets from screen state the moment they're no longer needed.
                        password = ""
                        secretKey = ""
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
            if (error.isNotEmpty()) MessageBox(error)
        }
    }
}
