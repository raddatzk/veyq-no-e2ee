package com.veyq.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Phase 1 (cr-0016) lands only the scaffold: the Compose head builds over `:client` / `:crypto`. The
// real connect → sign-in → home flow (VeyqTheme + AuthCard/VeyqField/PrimaryButton) arrives in phase 4.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Placeholder()
        }
    }
}

@Composable
private fun Placeholder() {
    MaterialTheme {
        Surface {
            Text("veyq")
        }
    }
}
