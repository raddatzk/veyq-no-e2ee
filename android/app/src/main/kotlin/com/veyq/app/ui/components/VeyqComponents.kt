package com.veyq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyq.app.ui.theme.VeyqTokens
import com.veyq.app.ui.theme.veyqColors

// The shared component contract on the Android surface — composables mirroring Apple's `AuthCard` /
// `VeyqField` / `PrimaryButton` / `MessageBox` (same names for cross-surface legibility). They read the
// exact veyq role colors from the theme rather than raw Material defaults.

/** Keyboard hint for [VeyqField] — the Android analog of Apple's `VeyqKeyboard`. */
enum class VeyqKeyboard { DEFAULT, EMAIL, URL }

/**
 * The branded auth card (wordmark + title + slotted content) centred on the app background. Responsive:
 * the card fills width on a phone and caps at a comfortable reading width (≤440.dp) on a tablet / large
 * window, per the parity "don't stretch the phone layout" rule.
 */
@Composable
fun AuthCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val c = veyqColors
    Box(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(VeyqTokens.radius))
                    .background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(VeyqTokens.radius))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = c.accent)
                        Text("veyq", fontSize = 28.sp, fontWeight = FontWeight.Black, color = c.text)
                    }
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = c.text)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 13.sp, color = c.muted)
                    }
                }
                content()
            }
        }
    }
}

/**
 * A labeled secure/plain text field matching the web/Apple form inputs. Secure entry uses a
 * [PasswordVisualTransformation] so the password + Secret Key are masked and never leave the device
 * (operator-blind). [testTag] = [label] gives the UI tests a stable handle.
 */
@Composable
fun VeyqField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    secure: Boolean = false,
    keyboard: VeyqKeyboard = VeyqKeyboard.DEFAULT,
    hint: String? = null,
) {
    val c = veyqColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 16.sp),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (keyboard) {
                    VeyqKeyboard.DEFAULT -> if (secure) KeyboardType.Password else KeyboardType.Text
                    VeyqKeyboard.EMAIL -> KeyboardType.Email
                    VeyqKeyboard.URL -> KeyboardType.Uri
                },
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(label)
                .clip(RoundedCornerShape(VeyqTokens.radiusSm))
                .background(c.surfaceMuted)
                .border(1.dp, c.line, RoundedCornerShape(VeyqTokens.radiusSm))
                .padding(horizontal = 11.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = c.muted, fontSize = 16.sp)
                }
                inner()
            },
        )
        if (hint != null) {
            Text(hint, fontSize = 12.sp, color = c.muted)
        }
    }
}

/** Primary (accent) full-width button with a busy state — mirrors Apple's `PrimaryButton`. */
@Composable
fun PrimaryButton(
    title: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = veyqColors
    val active = enabled && !busy
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(title)
            .clip(RoundedCornerShape(VeyqTokens.radiusSm))
            .background(if (active) c.accent else c.accent.copy(alpha = 0.5f))
            .clickable(enabled = active, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) "…" else title,
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Inline error / info message box — mirrors Apple's `MessageBox`. */
@Composable
fun MessageBox(text: String, error: Boolean = true) {
    val c = veyqColors
    Text(
        text,
        fontSize = 13.sp,
        color = if (error) c.danger else c.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VeyqTokens.radiusSm))
            .background(if (error) c.danger.copy(alpha = 0.14f) else c.surfaceMuted)
            .padding(10.dp)
            .semantics { contentDescription = text },
    )
}

/**
 * The Emergency-Kit import affordances — **Upload kit** (a file) and (when wired) **Scan QR** (the camera) —
 * the native analog of the web's in-field upload/scan icons, mirroring Apple's `KitImportBar`. Shared by the
 * sign-in screen and the unlock floor.
 */
@Composable
fun KitImportBar(
    label: String? = null,
    onUpload: () -> Unit,
    onScan: (() -> Unit)? = null,
) {
    val c = veyqColors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                "Upload kit",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = c.accent,
                modifier = Modifier.testTag("Upload kit").clickable(onClick = onUpload),
            )
            if (onScan != null) {
                Text(
                    "Scan QR",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.accent,
                    modifier = Modifier.testTag("Scan QR").clickable(onClick = onScan),
                )
            }
        }
        if (label != null) {
            Text(label, fontSize = 12.sp, color = c.muted)
        }
    }
}
