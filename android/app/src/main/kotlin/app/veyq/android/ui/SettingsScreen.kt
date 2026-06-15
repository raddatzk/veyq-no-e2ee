package app.veyq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Login

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(apiBase: String, session: Login.UnlockedSession, onBack: () -> Unit, onSignOut: () -> Unit) {
    Scaffold(
        containerColor = Veyq.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen", color = Veyq.Text, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Veyq.Surface),
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", color = Veyq.Text, fontSize = 24.sp) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Sprache und Darstellung")
            SettingRow("Darstellung", "Dunkel")
            SettingRow("Sprache", "Deutsch")

            Section("Account")
            SettingRow("E-Mail", session.email)

            Section("Eingeloggte Instanz")
            SettingRow("Instanz", apiBase)

            Button(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 12.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Veyq.SurfaceMuted, contentColor = Veyq.Danger),
            ) { Text("Trennen", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Veyq.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
            .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Veyq.Text, fontSize = 15.sp)
        Text(value, color = Veyq.Muted, fontSize = 15.sp)
    }
}
