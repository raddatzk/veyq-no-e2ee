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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.veyq.app.i18n.L10n
import com.veyq.app.i18n.LocalL10n
import com.veyq.app.i18n.SettingsPrefs
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
    // The reactive theme + language holder, created once and provided to the whole tree. Its theme
    // preference drives [VeyqTheme] (instead of the bare OS dark-mode) and its language preference re-renders
    // every string in place; "system" follows the OS — for language we keep [systemLanguage] in sync with the
    // configuration so a switch never needs an Activity recreation (the open Memory tree survives).
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val systemLang = config.locales[0].language
    val l10n = remember { L10n(SettingsPrefs(context), systemLang) }
    SideEffect { l10n.systemLanguage = systemLang }

    CompositionLocalProvider(LocalL10n provides l10n) {
        VeyqTheme(darkTheme = l10n.darkTheme(isSystemInDarkTheme())) {
            Surface(color = veyqColors.bg, modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    RootScreen()
                }
            }
        }
    }
}
