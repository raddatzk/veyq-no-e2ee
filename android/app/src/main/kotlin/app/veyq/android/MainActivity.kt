package app.veyq.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.veyq.android.ui.ConnectScreen
import app.veyq.android.ui.HomeScreen
import app.veyq.android.ui.LoginScreen
import app.veyq.android.ui.VeyqTheme
import app.veyq.crypto.Login

/**
 * Greenfield Android app entry. Slice 1: connect → login → landed, following iOS in Material 3 on top of
 * the `:core` crypto module. The real tab bar + MemoryHome arrive in slice 2.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VeyqTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthFlow()
                }
            }
        }
    }
}

private enum class Phase { Connect, Login, Landed }

@Composable
private fun AuthFlow() {
    var phase by remember { mutableStateOf(Phase.Connect) }
    var apiBase by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<Login.UnlockedSession?>(null) }

    when (phase) {
        Phase.Connect -> ConnectScreen(apiBase) { apiBase = it; phase = Phase.Login }
        Phase.Login -> LoginScreen(apiBase, onChangeInstance = { phase = Phase.Connect }) { session = it; phase = Phase.Landed }
        Phase.Landed -> HomeScreen(apiBase, session!!) { session = null; phase = Phase.Login }
    }
}
