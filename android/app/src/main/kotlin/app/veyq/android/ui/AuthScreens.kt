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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import app.veyq.crypto.EmergencyKit
import app.veyq.crypto.Instance
import app.veyq.crypto.Login
import app.veyq.crypto.ResetPassword
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
fun GhostButton(title: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Veyq.Text),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
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
fun ConnectScreen(initialUrl: String, onConnected: (String, String?) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var setupNeeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthCard(L10n.t("connect.title"), L10n.t("connect.sub")) {
        VeyqField(L10n.t("connect.url"), url, { url = it }, keyboard = KeyboardType.Uri)
        if (setupNeeded) MessageBox(L10n.t("connect.setupNeeded"), error = false)
        PrimaryButton(L10n.t("connect.connect"), busy) {
            busy = true; error = ""; setupNeeded = false
            scope.launch {
                try {
                    val base = normalizeApiBase(url)
                    val status = withContext(Dispatchers.IO) { Instance.getSetupStatus(base) }
                    if (status.setupRequired) setupNeeded = true else onConnected(base, status.appBaseUrl)
                } catch (e: Exception) {
                    error = L10n.t("connect.error")
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
    var remember0 by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var forgot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as? androidx.fragment.app.FragmentActivity

    if (forgot) {
        ResetPasswordScreen(apiBase, email, onBack = { forgot = false }, onLoggedIn = onLoggedIn)
        return
    }

    fun applyKit(text: String?) {
        val kit = text?.let { EmergencyKit.parse(it) }
        if (kit == null) { error = L10n.t("kit.importFailed"); return }
        email = kit.email; secretKey = kit.secretKey; error = ""
    }
    // Import a kit file (PDF / .veyqkit) → prefill. Read as Latin-1 so the ASCII token survives binary PDF bytes.
    val pickKit = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) applyKit(ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.ISO_8859_1) })
    }
    // Scan the kit QR with the camera → prefill.
    val scanKit = rememberLauncherForActivityResult(ScanContract()) { res -> if (res.contents != null) applyKit(res.contents) }

    AuthCard(L10n.t("login.title"), apiBase) {
        if (DeviceUnlock.isEnabled(ctx) && activity != null) {
            PrimaryButton("🔓 ${L10n.t("login.unlock")}", busy) {
                busy = true; error = ""
                scope.launch {
                    val creds = DeviceUnlock.load(activity)
                    if (creds == null) { error = L10n.t("login.unlockFailed"); busy = false; return@launch }
                    try {
                        val session = withContext(Dispatchers.IO) { Login.login(creds.apiBase, creds.email, creds.password, creds.secretKey) }
                        onLoggedIn(session)
                    } catch (e: Exception) {
                        error = L10n.t("login.unlockFailed")
                    } finally {
                        busy = false
                    }
                }
            }
        }
        VeyqField(L10n.t("common.email"), email, { email = it }, keyboard = KeyboardType.Email)
        VeyqField(L10n.t("common.password"), password, { password = it }, secure = true)
        VeyqField(L10n.t("common.secretKey"), secretKey, { secretKey = it }, secure = true, hint = L10n.t("login.secretHint"))
        if (DeviceUnlock.available(ctx)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Switch(checked = remember0, onCheckedChange = { remember0 = it })
                Text(L10n.t("login.remember"), fontSize = 14.sp, color = Veyq.Text, modifier = Modifier.padding(start = 8.dp))
            }
        }
        PrimaryButton(L10n.t("login.submit"), busy) {
            busy = true; error = ""
            scope.launch {
                try {
                    val session = withContext(Dispatchers.IO) { Login.login(apiBase, email, password, secretKey) }
                    if (remember0) DeviceUnlock.save(ctx, DeviceUnlock.Creds(apiBase, email, password, secretKey))
                    onLoggedIn(session)
                } catch (e: Exception) {
                    error = L10n.t("login.error")
                } finally {
                    busy = false
                }
            }
        }
        GhostButton("📄 ${L10n.t("kit.import")}") { pickKit.launch("*/*") }
        GhostButton("📷 ${L10n.t("kit.scan")}") {
            scanKit.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setPrompt(L10n.t("kit.scan")))
        }
        Text(L10n.t("reset.forgot"), fontSize = 13.sp, color = Veyq.Accent, modifier = Modifier.fillMaxWidth().clickable { forgot = true; error = "" })
        Text(L10n.t("login.otherInstance"), fontSize = 13.sp, color = Veyq.Muted, modifier = Modifier.fillMaxWidth().clickable { onChangeInstance() })
        if (error.isNotEmpty()) MessageBox(error)
    }
}

/**
 * Forgot-password recovery: request a reset email, then paste the link from it and enter the Secret Key +
 * a new password. Recovery needs the Secret Key (crypto factor) AND email access (the link is the email
 * factor). On success the user is signed in with the new password.
 */
@Composable
fun ResetPasswordScreen(apiBase: String, prefillEmail: String, onBack: () -> Unit, onLoggedIn: (Login.UnlockedSession) -> Unit) {
    var email by remember { mutableStateOf(prefillEmail) }
    var sent by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    AuthCard(L10n.t("reset.title"), apiBase) {
        Text(L10n.t("reset.requestHint"), fontSize = 13.sp, color = Veyq.Muted, modifier = Modifier.fillMaxWidth())
        VeyqField(L10n.t("common.email"), email, { email = it }, keyboard = KeyboardType.Email)
        if (sent) Text(L10n.t("reset.sent"), fontSize = 13.sp, color = Veyq.Accent, modifier = Modifier.fillMaxWidth())
        PrimaryButton(if (sent) L10n.t("reset.sendAgain") else L10n.t("reset.sendLink"), busy) {
            busy = true; error = ""
            scope.launch {
                try { withContext(Dispatchers.IO) { ResetPassword.requestReset(apiBase, email) }; sent = true }
                catch (e: Exception) { error = L10n.t("reset.requestFailed") }
                finally { busy = false }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = Veyq.Line, modifier = Modifier.padding(vertical = 4.dp))
        Text(L10n.t("reset.confirmHint"), fontSize = 13.sp, color = Veyq.Muted, modifier = Modifier.fillMaxWidth())
        VeyqField(L10n.t("reset.pasteLink"), link, { link = it })
        VeyqField(L10n.t("common.secretKey"), secretKey, { secretKey = it }, secure = true, hint = L10n.t("login.secretHint"))
        VeyqField(L10n.t("reset.newPassword"), newPassword, { newPassword = it }, secure = true)
        PrimaryButton(L10n.t("reset.setPassword"), busy) {
            val token = ResetPassword.parseToken(link)
            if (token == null || secretKey.isBlank() || newPassword.isBlank()) { error = L10n.t("reset.failed"); return@PrimaryButton }
            busy = true; error = ""
            scope.launch {
                try {
                    val addr = withContext(Dispatchers.IO) { ResetPassword.confirmReset(apiBase, token, secretKey, newPassword) }
                    val session = withContext(Dispatchers.IO) { Login.login(apiBase, addr, newPassword, secretKey) }
                    onLoggedIn(session)
                } catch (e: Exception) {
                    error = L10n.t("reset.failed")
                } finally {
                    busy = false
                }
            }
        }
        GhostButton(L10n.t("reset.backToLogin")) { onBack() }
        if (error.isNotEmpty()) MessageBox(error)
    }
}

/**
 * Brand-new invitee registering on mobile (reached via the veyq:// deep link from the browser banner):
 * create the account from the invite, show + share the Emergency Kit, then sign in and land in the group.
 */
@Composable
fun RegisterScreen(apiBase: String, parts: InviteLink.Parts, onCancel: () -> Unit, onRegistered: (Login.UnlockedSession) -> Unit) {
    var tenantName by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Member") }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var kit by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(parts.inviteId) {
        runCatching {
            withContext(Dispatchers.IO) {
                val m = app.veyq.crypto.RegisterViaInvitation.getInviteMetadata(apiBase, parts.inviteId)
                m.getJSONObject("tenant").getString("displayName") to m.getString("role")
            }
        }.onSuccess { tenantName = it.first; role = it.second }
            .onFailure { error = L10n.t("accept.failed") }
    }

    val roleLabel = if (role == "Owner") L10n.t("invite.owner") else L10n.t("invite.member")
    AuthCard(L10n.t("accept.createAccount"), if (tenantName.isNotEmpty()) String.format(L10n.t("accept.joinAs"), tenantName, roleLabel) else apiBase) {
        val secret = kit
        if (secret == null) {
            VeyqField(L10n.t("setup.displayName"), displayName, { displayName = it })
            VeyqField(L10n.t("common.email"), email, { email = it }, keyboard = KeyboardType.Email)
            VeyqField(L10n.t("common.password"), password, { password = it }, secure = true)
            if (parts.isProtected) VeyqField(L10n.t("accept.passphrase"), passphrase, { passphrase = it }, secure = true)
            PrimaryButton(L10n.t("accept.register"), busy) {
                busy = true; error = ""
                scope.launch {
                    try {
                        val res = withContext(Dispatchers.IO) {
                            if (role == "Owner")
                                app.veyq.crypto.RegisterViaInvitation.registerViaInvitation(apiBase, parts.inviteId, parts.bootstrapSecret, email, displayName, password)
                            else {
                                val tempKey = if (parts.isProtected)
                                    app.veyq.crypto.InviteMembers.unprotectTempKey(parts.encTempKey!!, parts.pwSalt!!, passphrase)
                                else parts.tempPrivateKey ?: ""
                                app.veyq.crypto.InviteMembers.registerViaMemberInvite(apiBase, parts.inviteId, parts.bootstrapSecret, tempKey, email, displayName, password)
                            }
                        }
                        kit = res.emergencyKit.secretKey
                    } catch (e: Exception) {
                        error = if (parts.isProtected) L10n.t("accept.wrongPassphrase") else L10n.t("accept.registerFailed")
                    } finally {
                        busy = false
                    }
                }
            }
            GhostButton(L10n.t("common.cancel")) { onCancel() }
        } else {
            Text(L10n.t("setup.kitTitle"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Veyq.Text)
            Text(L10n.t("setup.kitSub"), fontSize = 13.sp, color = Veyq.Muted)
            Text(secret, fontSize = 13.sp, color = Veyq.Text, modifier = Modifier
                .fillMaxWidth()
                .background(Veyq.SurfaceMuted, RoundedCornerShape(10.dp))
                .padding(10.dp))
            GhostButton("📄 ${L10n.t("kit.download")}") {
                EmergencyKitPdf.shareKit(ctx, EmergencyKit.KitData(apiBase, email, secret), displayName)
            }
            PrimaryButton(L10n.t("setup.kitProceed"), busy) {
                busy = true; error = ""
                scope.launch {
                    try {
                        val session = withContext(Dispatchers.IO) { Login.login(apiBase, email, password, secret) }
                        onRegistered(session)
                    } catch (e: Exception) {
                        error = L10n.t("login.error")
                    } finally {
                        busy = false
                    }
                }
            }
        }
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
