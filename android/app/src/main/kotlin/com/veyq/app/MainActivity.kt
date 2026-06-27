package com.veyq.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.veyq.app.ui.RootScreen
import com.veyq.app.ui.theme.VeyqTheme
import com.veyq.app.ui.theme.veyqColors

/**
 * The single activity hosting the Compose tree. Edge-to-edge: the veyq `bg` is drawn behind the system
 * bars (the [Surface]) and content is padded with `safeDrawing` so the auth card isn't under the status /
 * nav bars.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeyqApp()
        }
    }
}

@Composable
private fun VeyqApp() {
    VeyqTheme {
        Surface(color = veyqColors.bg, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                RootScreen()
            }
        }
    }
}
