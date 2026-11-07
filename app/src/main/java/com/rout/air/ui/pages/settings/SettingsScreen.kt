package com.rout.air.ui.pages.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import com.rout.air.core.audio.AudioChannel
import com.rout.air.core.audio.AudioMix
import com.rout.air.core.model.CaptureConfig
import com.rout.air.core.model.LiveConfig
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import com.rout.air.ui.pages.settings.sections.AdvancedSettings
import com.rout.air.ui.pages.settings.sections.AudioSettings
import com.rout.air.ui.pages.settings.sections.GeneralSettings
import com.rout.air.ui.pages.settings.sections.LiveSettings
import com.rout.air.ui.pages.settings.sections.OutputSettings
import com.rout.air.ui.pages.settings.sections.SceneCollectionsSettings
import com.rout.air.ui.pages.settings.sections.VideoSettings

private enum class SettingsPage(@StringRes val titleRes: Int) {
    Root(R.string.tab_setelan),
    General(R.string.settings_entry_umum),
    Stream(R.string.settings_entry_siaran),
    Output(R.string.settings_entry_output),
    Audio(R.string.tab_audio),
    Video(R.string.settings_entry_video),
    Collections(R.string.settings_entry_adegan),
    Advanced(R.string.settings_entry_lanjutan),
}


/**
 * Setelan dipisah per halaman supaya tidak menumpuk dalam satu dialog:
 * Umum, Siaran, Output, Audio, Video, dan Lanjutan.
 */
@Composable
fun SettingsScreen(
    config: CaptureConfig,
    canvasConfig: CaptureConfig,
    mix: AudioMix,
    liveConfig: LiveConfig,
    onConfigChange: (CaptureConfig) -> Unit,
    onMixChange: (AudioChannel) -> Unit,
    onMasterEnabledChange: (Boolean) -> Unit,
    onLiveConfigChange: (LiveConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SettingsPage.Root) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Satu posisi gulir untuk seluruh halaman; tab Output memakainya untuk
    // kembali ke atas saat berpindah tab.
    val scrollState = rememberScrollState()

    BackHandler(enabled = page != SettingsPage.Root) { page = SettingsPage.Root }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                horizontal = if (landscape) 16.dp else 20.dp,
                vertical = if (landscape) 10.dp else 16.dp,
            ),
    ) {
        if (page == SettingsPage.Root) {
            Text(stringResource(R.string.settings_setelan), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.settings_preset_rekaman_dan_perangkat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            val entries = listOf(
                SettingsEntryData(
                    stringResource(R.string.settings_entry_umum),
                    stringResource(R.string.settings_entry_umum_desc),
                    SettingsPage.General,
                ),
                SettingsEntryData(
                    stringResource(R.string.settings_entry_siaran),
                    stringResource(R.string.settings_entry_siaran_desc),
                    SettingsPage.Stream,
                ),
                SettingsEntryData(
                    stringResource(R.string.settings_entry_output),
                    stringResource(R.string.settings_entry_output_desc),
                    SettingsPage.Output,
                ),
                SettingsEntryData(
                    stringResource(R.string.settings_entry_audio),
                    stringResource(R.string.settings_entry_audio_desc),
                    SettingsPage.Audio,
                ),
                SettingsEntryData(
                    stringResource(R.string.settings_entry_video),
                    stringResource(R.string.settings_entry_video_desc),
                    SettingsPage.Video,
                ),
                SettingsEntryData(
                    stringResource(R.string.settings_entry_adegan),
                    stringResource(R.string.settings_entry_adegan_desc),
                    SettingsPage.Collections,
                ),
                SettingsEntryData(
                    stringResource(R.string.settings_entry_lanjutan),
                    stringResource(R.string.settings_entry_lanjutan_desc),
                    SettingsPage.Advanced,
                ),
            )
            if (landscape) {
                // Landscape: dua kolom agar semua pintasan terlihat sekaligus.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        entries.filterIndexed { index, _ -> index % 2 == 0 }.forEach { entry ->
                            SettingsEntry(entry.title, entry.subtitle) { page = entry.page }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        entries.filterIndexed { index, _ -> index % 2 == 1 }.forEach { entry ->
                            SettingsEntry(entry.title, entry.subtitle) { page = entry.page }
                        }
                    }
                }
            } else {
                entries.forEach { entry ->
                    SettingsEntry(entry.title, entry.subtitle) { page = entry.page }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page = SettingsPage.Root }) { Text(stringResource(R.string.common_kembali_panah)) }
                Text(stringResource(page.titleRes), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))

            when (page) {
                SettingsPage.Video -> VideoSettings(config, canvasConfig, onConfigChange)
                SettingsPage.Collections -> SceneCollectionsSettings()
                SettingsPage.Output -> OutputSettings(
                    config = config,
                    mix = mix,
                    live = liveConfig,
                    scrollState = scrollState,
                    onConfigChange = onConfigChange,
                    onLiveConfigChange = onLiveConfigChange,
                )
                SettingsPage.General -> GeneralSettings()
                SettingsPage.Stream -> LiveSettings(liveConfig, onLiveConfigChange)
                SettingsPage.Audio -> AudioSettings(
                    config = config,
                    mix = mix,
                    onMixChange = onMixChange,
                    onMasterEnabledChange = onMasterEnabledChange,
                    onConfigChange = onConfigChange,
                )
                SettingsPage.Advanced -> AdvancedSettings(
                    config = config,
                    canvasConfig = canvasConfig,
                    live = liveConfig,
                    onConfigChange = onConfigChange,
                )
                SettingsPage.Root -> Unit
            }
        }
    }
}


/** Satu pintasan di halaman utama Setelan. */
private data class SettingsEntryData(
    val title: String,
    val subtitle: String,
    val page: SettingsPage,
)

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.settings_chevron), style = MaterialTheme.typography.titleLarge)
        }
    }
}


internal enum class OutputTab(@StringRes val labelRes: Int) {
    Streaming(R.string.settings_entry_siaran),
    Recording(R.string.tab_rekaman),
    Audio(R.string.tab_audio),
}


/** Bitrate yang diatur blok setelan video pada tab yang sedang dibuka. */
internal enum class BitrateTarget { Streaming, Recording }


/** Template resolusi benchmark; semua sisi genap. */
internal enum class BenchResolution(val label: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P4K("4K", 3840, 2160),
}
