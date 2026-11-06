package com.rout.air.ui.pages.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rout.air.data.settings.AppLanguage
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.theme.AccentChoice
import com.rout.air.ui.theme.Appearance
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.InfoRow
import com.rout.air.ui.pages.settings.components.SectionTitle

@Composable
internal fun GeneralSettings() {
    val accent by Appearance.accent.collectAsState()
    val amoled by Appearance.amoled.collectAsState()

    SectionTitle(stringResource(R.string.settings_general))
    // Bahasa aplikasi hanya bisa diganti mulai Android 13 (LocaleManager).
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    if (AppLanguage.supported(context)) {
        Text(stringResource(R.string.settings_language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.tags.forEach { tag ->
                FilterChip(
                    selected = language == tag,
                    onClick = {
                        AppLanguage.set(context, tag)
                        language = tag
                    },
                    label = {
                        Text(
                            stringResource(
                                if (tag == "en") R.string.settings_language_en
                                else R.string.settings_language_id,
                            ),
                        )
                    },
                )
            }
        }
    } else {
        InfoRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_id))
        Hint(stringResource(R.string.settings_language_android13))
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.settings_theme))
    Hint(stringResource(R.string.settings_accent_hint))
    Spacer(Modifier.height(8.dp))
    // Enam pilihan aksen; dipecah dua baris supaya tetap muat di layar sempit.
    val choices = AccentChoice.entries
    choices.chunked(3).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            row.forEach { choice ->
                FilterChip(
                    selected = accent == choice,
                    onClick = { Appearance.setAccent(choice) },
                    label = { Text(accentLabel(choice)) },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_amoled))
            Hint(stringResource(R.string.settings_amoled_hint))
        }
        Switch(
            checked = amoled,
            onCheckedChange = { Appearance.setAmoled(it) },
        )
    }

    Spacer(Modifier.height(12.dp))
    InfoRow(stringResource(R.string.settings_confirm_exit), stringResource(R.string.settings_unsupported_android))
    Hint(stringResource(R.string.settings_general_hint))
}

/** Nama aksen mengikuti bahasa aplikasi. */
@Composable
internal fun accentLabel(choice: AccentChoice): String = stringResource(
    when (choice) {
        AccentChoice.Cyan -> R.string.settings_accent_cyan
        AccentChoice.Green -> R.string.settings_accent_green
        AccentChoice.Blue -> R.string.settings_accent_blue
        AccentChoice.Purple -> R.string.settings_accent_purple
        AccentChoice.Amber -> R.string.settings_accent_amber
        AccentChoice.Pink -> R.string.settings_accent_pink
    },
)
