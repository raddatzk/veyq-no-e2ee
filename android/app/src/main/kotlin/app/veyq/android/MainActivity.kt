package app.veyq.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.veyq.crypto.Media

/**
 * Greenfield app entry. The real UI (space switcher, MemoryHome, detail, upload) follows iOS in
 * Material 3 and is built as slices on top of the `:core` crypto module; this is the bootable shell
 * that proves the app↔core wiring.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootScreen()
                }
            }
        }
    }
}

@Composable
private fun RootScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("veyq", style = MaterialTheme.typography.headlineLarge)
        Text(
            "core linked · frame size ${Media.DEFAULT_CHUNK_SIZE}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
