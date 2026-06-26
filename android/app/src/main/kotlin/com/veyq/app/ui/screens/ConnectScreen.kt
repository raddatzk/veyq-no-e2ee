package com.veyq.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.veyq.app.ui.AppModel
import com.veyq.app.ui.components.AuthCard
import com.veyq.app.ui.components.MessageBox
import com.veyq.app.ui.components.PrimaryButton
import com.veyq.app.ui.components.VeyqField
import com.veyq.app.ui.components.VeyqKeyboard
import kotlinx.coroutines.launch

/**
 * Connect to a chosen instance — the Android twin of Apple's `ConnectView`. Native never bootstraps (setup
 * is browser-only), so if the instance still needs setup we say so rather than offering a setup form;
 * otherwise the model advances to sign-in.
 */
@Composable
fun ConnectScreen(app: AppModel) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var setupNeeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthCard(title = "Connect to your instance", subtitle = "Enter the address of your veyq server.") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VeyqField(
                label = "Instance URL",
                value = url,
                onValueChange = { url = it },
                placeholder = "https://memories.example.com",
                keyboard = VeyqKeyboard.URL,
            )
            if (setupNeeded) {
                MessageBox(
                    "This instance still needs first-run setup. Finish it in a web browser, then connect here.",
                    error = false,
                )
            }
            PrimaryButton(title = "Connect", busy = busy, enabled = url.trim().isNotEmpty()) {
                busy = true
                error = ""
                setupNeeded = false
                scope.launch {
                    when (val result = app.connect(url)) {
                        is AppModel.ConnectResult.SetupNeeded -> setupNeeded = true
                        is AppModel.ConnectResult.Failed -> error = result.message
                        is AppModel.ConnectResult.Connected -> Unit // the model advances the phase
                    }
                    busy = false
                }
            }
            if (error.isNotEmpty()) MessageBox(error)
        }
    }
}
