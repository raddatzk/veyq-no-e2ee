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
import com.veyq.app.i18n.LocalL10n
import com.veyq.app.ui.theme.VeyqTokens
import com.veyq.app.ui.theme.veyqColors

/**
 * The per-device unlock-level choice (uc.account.set-device-security; ADR 0005 §8–13), the Android twin of
 * Apple's `DeviceSecurityChooser`. Shown on the floor at unlock time with **no pre-selection** — the user
 * must actively decide. The title + subtitle are carried as i18n keys ([titleKey] / [subKey]) and resolved
 * through `L10n` at render so the chooser localizes with the rest of the UI.
 */
enum class DeviceUnlockLevel(val titleKey: String, val subKey: String) {
    SECRET_KEY_EACH_TIME("device.secretEachTime.title", "device.secretEachTime.sub"),
    ONLY_PASSWORD("device.onlyPassword.title", "device.onlyPassword.sub"),
    BIOMETRIC("device.biometric.title", "device.biometric.sub"),
    NO_LOGIN("device.noLogin.title", "device.noLogin.sub"),
}

@Composable
fun DeviceSecurityChooser(
    selection: DeviceUnlockLevel?,
    onSelect: (DeviceUnlockLevel) -> Unit,
    biometricAvailable: Boolean = true,
) {
    val c = veyqColors
    val l = LocalL10n.current
    // L2 needs an enrolled biometric; the rest are always offerable (L3 is an explicit, OS-lock-only choice).
    fun available(level: DeviceUnlockLevel) = level != DeviceUnlockLevel.BIOMETRIC || biometricAvailable
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(l.t("device.prompt"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        DeviceUnlockLevel.entries.forEach { level ->
            val selected = selection == level
            val isAvailable = available(level)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(VeyqTokens.radiusSm))
                    .background(c.surfaceMuted)
                    .border(
                        BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line),
                        RoundedCornerShape(VeyqTokens.radiusSm),
                    )
                    .clickable(enabled = isAvailable) { onSelect(level) }
                    .padding(11.dp)
                    .testTag("level.${level.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            l.t(level.titleKey),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAvailable) c.text else c.muted,
                        )
                        if (!isAvailable) {
                            Text(
                                l.t("device.notAvailable"),
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
                    Text(l.t(level.subKey), fontSize = 12.sp, color = c.muted)
                }
                RadioButton(selected = selected, onClick = { if (isAvailable) onSelect(level) }, enabled = isAvailable)
            }
        }
    }
}
