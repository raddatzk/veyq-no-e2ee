package com.veyq.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veyq.app.ui.screens.ConnectScreen
import com.veyq.app.ui.screens.HomeScreen
import com.veyq.app.ui.screens.SignInScreen

/**
 * Root of the universal app — the Android twin of Apple's `RootView`. Switches between the connect →
 * sign-in → home phases the [AppModel] drives. One Compose tree, adaptive across phone / tablet (the cards
 * cap their width rather than stretch).
 */
@Composable
fun RootScreen(app: AppModel = viewModel()) {
    when (app.phase) {
        AppModel.Phase.CONNECT -> ConnectScreen(app)
        AppModel.Phase.SIGN_IN -> SignInScreen(app)
        AppModel.Phase.HOME -> HomeScreen(app)
    }
}
