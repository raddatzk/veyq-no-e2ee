package com.veyq.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyq.app.i18n.LocalL10n
import com.veyq.app.ui.theme.veyqColors

/**
 * The theme + language Settings (cr-0043) — the Android parity of the web ProfileMenu and Apple's
 * `SettingsView`, reached from the gear in the spaces-rail profile bar. A `ModalBottomSheet` so it overlays
 * the Memory shell **without** unwinding any navigation: picking a theme flips the whole palette live and
 * picking a language re-renders every string in place (both driven by the reactive `L10n`, both persisted),
 * and the open Memory path/tree behind the sheet is never reloaded. Account email + instance are shown for
 * content parity. Sign-out / untrust stay in the rail (idiomatic, ADR 0010).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(email: String, instance: String, onDismiss: () -> Unit) {
    val c = veyqColors
    val l = LocalL10n.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(l.t("settings.title"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text)

            // Appearance — system / light / dark, applied live via L10n.setTheme.
            Setting(label = l.t("settings.appearance")) {
                Segmented(
                    options = listOf(
                        Triple("theme.system", l.t("settings.system"), "system"),
                        Triple("theme.light", l.t("settings.light"), "light"),
                        Triple("theme.dark", l.t("settings.dark"), "dark"),
                    ),
                    selected = l.theme,
                    onSelect = l::applyTheme,
                )
            }

            // Language — system / English / Deutsch (endonyms, like Apple), applied live via setPreference.
            Setting(label = l.t("settings.language")) {
                Segmented(
                    options = listOf(
                        Triple("lang.system", l.t("settings.system"), "system"),
                        Triple("lang.en", "English", "en"),
                        Triple("lang.de", "Deutsch", "de"),
                    ),
                    selected = l.preference,
                    onSelect = l::applyLanguage,
                )
            }

            Setting(label = l.t("settings.account")) {
                Text(email, fontSize = 14.sp, color = c.text)
            }
            Setting(label = l.t("settings.instance")) {
                Text(instance, fontSize = 13.sp, color = c.muted)
            }
        }
    }
}

/** A labelled settings row: a muted caption above its control. */
@Composable
private fun Setting(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = veyqColors.muted)
        content()
    }
}

/** A single-choice segmented control over (testTag, label, value) options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(
    options: List<Triple<String, String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (tag, label, value) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag(tag),
            ) {
                Text(label)
            }
        }
    }
}
