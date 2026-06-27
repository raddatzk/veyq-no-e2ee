package com.veyq.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyq.app.ui.theme.VeyqTokens
import com.veyq.app.ui.theme.veyqColors

/**
 * The per-device unlock-level choice (uc.account.set-device-security; ADR 0005 §8–13), the Android twin of
 * Apple's `DeviceSecurityChooser`. Shown on the floor at unlock time with **no pre-selection** — the user
 * must actively decide. cr-0021 ships L0 + L1; the passwordless notches are previewed disabled ("Coming
 * soon", cr-0022) so the chooser extends rather than gets rewritten.
 */
enum class DeviceUnlockLevel(val title: String, val subtitle: String, val available: Boolean) {
    SECRET_KEY_EACH_TIME("Secret Key each time", "Most private — enter your password and Secret Key every time.", true),
    ONLY_PASSWORD("Only my password", "Trust this device: next time just your password, no Secret Key.", true),
    BIOMETRIC("Fingerprint / Face", "Unlock with a touch — no password.", false),
    NO_LOGIN("Open straight in", "No prompt at all — relies only on your device’s lock screen.", false),
}

@Composable
fun DeviceSecurityChooser(selection: DeviceUnlockLevel?, onSelect: (DeviceUnlockLevel) -> Unit) {
    val c = veyqColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How should this device unlock next time?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        DeviceUnlockLevel.entries.forEach { level ->
            val selected = selection == level
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(VeyqTokens.radiusSm))
                    .background(c.surfaceMuted)
                    .border(
                        BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line),
                        RoundedCornerShape(VeyqTokens.radiusSm),
                    )
                    .clickable(enabled = level.available) { onSelect(level) }
                    .padding(11.dp)
                    .testTag("level.${level.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            level.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (level.available) c.text else c.muted,
                        )
                        if (!level.available) {
                            Text(
                                "Coming soon",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(c.surface)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(level.subtitle, fontSize = 12.sp, color = c.muted)
                }
                RadioButton(selected = selected, onClick = { if (level.available) onSelect(level) }, enabled = level.available)
            }
        }
    }
}
