package app.veyq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.veyq.crypto.Instance
import app.veyq.crypto.Login
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Normalize a user-typed instance URL (accept bare host, default https, strip trailing slash). */
fun normalizeApiBase(input: String): String {
    var s = input.trim().trimEnd('/')
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
    return s
}

@Composable
fun AuthCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Veyq.Bg).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .border(1.dp, Veyq.Line, RoundedCornerShape(Veyq.Radius))
                .background(Veyq.Surface, RoundedCornerShape(Veyq.Radius))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("veyq", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Veyq.Text)
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Veyq.Text)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Veyq.Muted)
            content()
        }
    }
}

@Composable
fun VeyqField(label: String, value: String, onValueChange: (String) -> Unit, secure: Boolean = false, hint: String? = null, keyboard: KeyboardType = KeyboardType.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Veyq.Text)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Veyq.SurfaceMuted,
                unfocusedContainerColor = Veyq.SurfaceMuted,
                focusedBorderColor = Veyq.Accent,
                unfocusedBorderColor = Veyq.Line,
                focusedTextColor = Veyq.Text,
                unfocusedTextColor = Veyq.Text,
            ),
        )
        if (hint != null) Text(hint, fontSize = 12.sp, color = Veyq.Muted)
    }
}

@Composable
fun PrimaryButton(title: String, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Veyq.Accent, contentColor = Color.White),
    ) {
        Text(if (busy) "…" else title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MessageBox(text: String, error: Boolean = true) {
    Text(
        text,
        fontSize = 13.sp,
        color = if (error) Veyq.Danger else Veyq.Muted,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (error) Veyq.Danger.copy(alpha = 0.14f) else Veyq.SurfaceMuted, RoundedCornerShape(10.dp))
            .padding(10.dp),
    )
}

@Composable
fun ConnectScreen(initialUrl: String, onConnected: (String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var setupNeeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthCard("Mit einer Instanz verbinden", "Gib die Adresse deiner veyq-Instanz ein.") {
        VeyqField("Instanz-URL", url, { url = it }, keyboard = KeyboardType.Uri)
        if (setupNeeded) MessageBox("Diese Instanz ist noch nicht eingerichtet — bitte im Web einrichten.", error = false)
        PrimaryButton("Verbinden", busy) {
            busy = true; error = ""; setupNeeded = false
            scope.launch {
                try {
                    val base = normalizeApiBase(url)
                    val status = withContext(Dispatchers.IO) { Instance.getSetupStatus(base) }
                    if (status.setupRequired) setupNeeded = true else onConnected(base)
                } catch (e: Exception) {
                    error = "Konnte die Instanz nicht erreichen. Prüfe die Adresse."
                } finally {
                    busy = false
                }
            }
        }
        if (error.isNotEmpty()) MessageBox(error)
    }
}

@Composable
fun LoginScreen(apiBase: String, onChangeInstance: () -> Unit, onLoggedIn: (Login.UnlockedSession) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AuthCard("Anmelden", apiBase) {
        VeyqField("E-Mail", email, { email = it }, keyboard = KeyboardType.Email)
        VeyqField("Passwort", password, { password = it }, secure = true)
        VeyqField("Secret Key", secretKey, { secretKey = it }, secure = true, hint = "Aus deinem Emergency Kit.")
        PrimaryButton("Anmelden", busy) {
            busy = true; error = ""
            scope.launch {
                try {
                    val session = withContext(Dispatchers.IO) { Login.login(apiBase, email, password, secretKey) }
                    onLoggedIn(session)
                } catch (e: Exception) {
                    error = "Anmeldung fehlgeschlagen. Prüfe E-Mail, Passwort und Secret Key."
                } finally {
                    busy = false
                }
            }
        }
        Text("Andere Instanz", fontSize = 13.sp, color = Veyq.Muted, modifier = Modifier.fillMaxWidth())
        if (error.isNotEmpty()) MessageBox(error)
    }
}

@Composable
fun LandedScreen(session: Login.UnlockedSession, apiBase: String, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    AuthCard("Eingeloggt", session.email) {
        Text("Slice 2 ersetzt das durch die Tab-Bar + MemoryHome.", fontSize = 12.sp, color = Veyq.Muted)
        PrimaryButton("Abmelden") {
            val token = session.sessionToken
            onSignOut()
            scope.launch { withContext(Dispatchers.IO) { runCatching { Login.logout(apiBase, token) } } }
        }
    }
}
