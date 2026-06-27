package com.veyq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veyq.app.ui.screens.ConnectScreen
import com.veyq.app.ui.screens.HomeScreen
import com.veyq.app.ui.screens.SignInScreen
import com.veyq.app.ui.screens.UnlockScreen
import com.veyq.app.ui.theme.veyqColors

/**
 * Root of the universal app — the Android twin of Apple's `RootView`. Switches between the launching →
 * connect → sign-in → unlock → home phases the [AppModel] drives. On launch it runs the passwordless
 * auto-restore (cr-0022) with the hosting activity (BiometricPrompt needs a FragmentActivity).
 */
@Composable
fun RootScreen(app: AppModel = viewModel()) {
    val activity = LocalContext.current as? FragmentActivity
    LaunchedEffect(Unit) { app.bootstrap(activity) }

    when (app.phase) {
        AppModel.Phase.LAUNCHING -> Box(
            modifier = Modifier.fillMaxSize().background(veyqColors.bg),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        AppModel.Phase.CONNECT -> ConnectScreen(app)
        AppModel.Phase.SIGN_IN -> SignInScreen(app)
        AppModel.Phase.UNLOCK -> UnlockScreen(app)
        AppModel.Phase.HOME -> HomeScreen(app)
    }
}
