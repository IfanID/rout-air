package com.rout.air.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rout.air.core.AudioChannel
import com.rout.air.core.AudioChannelId
import com.rout.air.core.AudioMix
import com.rout.air.core.RecordingState
import com.rout.air.data.AudioMixer
import com.rout.air.ui.theme.Divider
import com.rout.air.ui.theme.ErrorRed
import com.rout.air.ui.theme.SurfaceRaised
import com.rout.air.ui.theme.TextSecondary
import com.rout.air.ui.theme.TextTertiary
import com.rout.air.ui.theme.WarningAmber
import androidx.compose.ui.res.stringResource
import com.rout.air.R

/**
 * Halaman Audio: dock Audio Mixer OBS dengan toolbar bawah, kanal baris/kolom,
 * context menu, Advanced Audio Properties, Filters, dan source properties.
 */
@Composable
fun AudioScreen(
    state: RecordingState,
    mix: AudioMix,
    onChannelChange: (AudioChannel) -> Unit,
    onMasterEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val levels by AudioMixer.levels.collectAsState()
    val runtime by AudioMixer.runtime.collectAsState()

    var optionsOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var appPickerOpen by remember { mutableStateOf(false) }
    var filtersTarget by remember { mutableStateOf<AudioChannelId?>(null) }
    var propertiesTarget by remember { mutableStateOf<AudioChannelId?>(null) }
    var renameTarget by remember { mutableStateOf<AudioChannelId?>(null) }
    var deviceTarget by remember { mutableStateOf<AudioChannelId?>(null) }

    val sessionLive = state is RecordingState.Recording || state is RecordingState.Previewing
    val activeIds = mix.channels.filter { channel ->
        channel.enabled && sessionLive && runtime.running && runtime.ready(channel.id)
    }.mapTo(HashSet()) { it.id }
    val visible = rankedChannels(mix, activeIds)
    val hiddenCount = mix.channels.count { it.hidden }
    val readyCount = activeIds.size

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.audio_audio_mixer), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = mixerSubtitle(state, mix, readyCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mix.masterEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            WarningAmber
                        },
                    )
                }
                if (sessionLive) {
                    Text(
                        text = if (runtime.running) "MIXER AKTIF" else "AUDIO OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (runtime.running) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            ErrorRed
                        },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioHeaderAction("＋ Application Audio") { appPickerOpen = true }
                if (mix.channels.any { it.global && it.capture == com.rout.air.core.AudioCaptureKind.Microphone }) {
                    AudioHeaderAction("Ganti mikrofon") {
                        deviceTarget = AudioChannelId.Microphone
                    }
                }
            }

            if (!mix.masterEnabled) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = WarningAmber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.audio_rekam_audio_dimatikan_mixer_tetap_berjal),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningAmber,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onMasterEnabledChange(true) }) {
                            Text(stringResource(R.string.audio_nyalakan))
                        }
                    }
                }
            }

            if (mix.pushToMuteEnabled || mix.pushToTalkEnabled) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mix.pushToMuteEnabled) {
                        HoldButton(
                            label = stringResource(R.string.audio_tahan_bisukan_mic),
                            activeColor = ErrorRed,
                            onHoldChange = AudioMixer::setPushToMuteActive,
                        )
                    }
                    if (mix.pushToTalkEnabled) {
                        HoldButton(
                            label = stringResource(R.string.audio_tahan_bicara),
                            activeColor = MaterialTheme.colorScheme.primary,
                            onHoldChange = AudioMixer::setPushToTalkActive,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (visible.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.audio_tidak_ada_sumber_yang_ditampilkan), color = TextSecondary)
                        Text(
                            "Aktifkan “Tampilkan sumber nonaktif” di menu Opsi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                    }
                } else if (mix.verticalLayout) {
                    VerticalMixer(
                        mix = mix,
                        visible = visible,
                        activeIds = activeIds,
                        levels = levels,
                        onChannelChange = onChannelChange,
                        onUnhideAll = {
                            mix.channels.filter { it.hidden }.forEach {
                                onChannelChange(it.copy(hidden = false))
                            }
                        },
                        onRename = { renameTarget = it },
                        onFilters = { filtersTarget = it },
                        onProperties = { propertiesTarget = it },
                        onChangeDevice = { deviceTarget = it },
                    )
                } else {
                    HorizontalMixer(
                        mix = mix,
                        visible = visible,
                        activeIds = activeIds,
                        levels = levels,
                        onChannelChange = onChannelChange,
                        onUnhideAll = {
                            mix.channels.filter { it.hidden }.forEach {
                                onChannelChange(it.copy(hidden = false))
                            }
                        },
                        onRename = { renameTarget = it },
                        onFilters = { filtersTarget = it },
                        onProperties = { propertiesTarget = it },
                        onChangeDevice = { deviceTarget = it },
                    )
                }
            }
        }

        HorizontalDivider(color = Divider)
        MixerToolbar(
            mix = mix,
            hiddenCount = hiddenCount,
            optionsOpen = optionsOpen,
            onOptionsOpenChange = { optionsOpen = it },
            onToggleHidden = {
                if (hiddenCount > 0) AudioMixer.setShowHidden(!mix.showHidden)
            },
            onToggleLayout = {
                AudioMixer.setVerticalLayout(!mix.verticalLayout)
            },
            onOpenAdvanced = { advancedOpen = true },
            onAddApplication = { appPickerOpen = true },
            onUnhideAll = {
                mix.channels.filter { it.hidden }.forEach {
                    onChannelChange(it.copy(hidden = false))
                }
            },
            onShowHidden = { AudioMixer.setShowHidden(it) },
            onShowInactive = { AudioMixer.setShowInactive(it) },
            onKeepHiddenLast = { AudioMixer.setKeepHiddenLast(it) },
            onKeepInactiveLast = { AudioMixer.setKeepInactiveLast(it) },
        )
    }

    if (advancedOpen) {
        AdvancedAudioDialog(
            mix = mix,
            activeIds = mix.channels.filter { it.enabled }.mapTo(HashSet()) { it.id },
            onChannelChange = onChannelChange,
            onDismiss = { advancedOpen = false },
        )
    }

    filtersTarget?.let { id ->
        val channel = mix.channel(id)
        FiltersDialog(
            channel = channel,
            canPaste = AudioMixer.hasFilterClipboard(),
            onChannelChange = onChannelChange,
            onCopy = { AudioMixer.copyFilters(id) },
            onPaste = {
                AudioMixer.pasteFilters(id)
            },
            onDismiss = { filtersTarget = null },
        )
    }

    propertiesTarget?.let { id ->
        SourcePropertiesDialog(
            channel = mix.channel(id),
            onChannelChange = onChannelChange,
            onOpenRename = {
                propertiesTarget = null
                renameTarget = id
            },
            onOpenFilters = {
                propertiesTarget = null
                filtersTarget = id
            },
            onOpenDevicePicker = {
                propertiesTarget = null
                deviceTarget = id
            },
            onDismiss = { propertiesTarget = null },
        )
    }

    renameTarget?.let { id ->
        RenameChannelDialog(
            channel = mix.channel(id),
            onRename = { name ->
                onChannelChange(mix.channel(id).copy(name = name))
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deviceTarget?.let { id ->
        AudioDevicePickerDialog(
            currentId = mix.channel(id).deviceId,
            onSelect = { option ->
                AudioMixer.changeInputDevice(
                    id = id,
                    deviceId = option?.id ?: 0,
                    deviceName = option?.name,
                    deviceType = option?.type ?: 0,
                )
                deviceTarget = null
            },
            onDismiss = { deviceTarget = null },
        )
    }

    if (appPickerOpen) {
        ApplicationAudioPickerDialog(
            onSelect = { app ->
                AudioMixer.addApplicationAudio(
                    uid = app.uid,
                    packageName = app.packageName,
                    label = app.label,
                )
                appPickerOpen = false
            },
            onDismiss = { appPickerOpen = false },
        )
    }
}

/**
 * Preferensi global (layout, show hidden, dsb.) diubah langsung lewat
 * AudioMixer karena bukan milik satu kanal.
 */

@Composable
private fun HorizontalMixer(
    mix: AudioMix,
    visible: List<AudioChannel>,
    activeIds: Set<AudioChannelId>,
    levels: com.rout.air.core.AudioLevels,
    onChannelChange: (AudioChannel) -> Unit,
    onUnhideAll: () -> Unit,
    onRename: (AudioChannelId) -> Unit,
    onFilters: (AudioChannelId) -> Unit,
    onProperties: (AudioChannelId) -> Unit,
    onChangeDevice: (AudioChannelId) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        visible.forEachIndexed { index, channel ->
            AudioChannelRow(
                channel = channel,
                category = categoryFor(channel, channel.id in activeIds),
                level = levels.forChannel(channel.id),
                active = channel.id in activeIds && !channel.muted,
                usePercent = mix.volumePercent,
                decay = mix.meterDecay,
                peakType = mix.peakMeterType,
                onChannelChange = onChannelChange,
                onUnhideAll = onUnhideAll,
                onRename = { onRename(channel.id) },
                onFilters = { onFilters(channel.id) },
                onProperties = { onProperties(channel.id) },
                canPasteFilters = AudioMixer.hasFilterClipboard(),
                onCopyFilters = { AudioMixer.copyFilters(channel.id) },
                onPasteFilters = { AudioMixer.pasteFilters(channel.id) },
                onChangeDevice = { onChangeDevice(channel.id) },
            )
            if (index < visible.lastIndex) HorizontalDivider(color = Divider)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.audio_audio_internal_memakai_audioplaybackcapt) +
                "atau yang melarang tangkap audio akan terekam senyap.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VerticalMixer(
    mix: AudioMix,
    visible: List<AudioChannel>,
    activeIds: Set<AudioChannelId>,
    levels: com.rout.air.core.AudioLevels,
    onChannelChange: (AudioChannel) -> Unit,
    onUnhideAll: () -> Unit,
    onRename: (AudioChannelId) -> Unit,
    onFilters: (AudioChannelId) -> Unit,
    onProperties: (AudioChannelId) -> Unit,
    onChangeDevice: (AudioChannelId) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val count = visible.size.coerceAtLeast(1)
        val columnWidth = maxOf(132.dp, maxWidth / count.toFloat())

        Row(
            modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
        ) {
            visible.forEachIndexed { index, channel ->
                AudioChannelColumn(
                    channel = channel,
                    category = categoryFor(channel, channel.id in activeIds),
                    level = levels.forChannel(channel.id),
                    active = channel.id in activeIds && !channel.muted,
                    usePercent = mix.volumePercent,
                    decay = mix.meterDecay,
                    peakType = mix.peakMeterType,
                    width = columnWidth,
                    onChannelChange = onChannelChange,
                    onUnhideAll = onUnhideAll,
                    onRename = { onRename(channel.id) },
                    onFilters = { onFilters(channel.id) },
                    onProperties = { onProperties(channel.id) },
                    canPasteFilters = AudioMixer.hasFilterClipboard(),
                    onCopyFilters = { AudioMixer.copyFilters(channel.id) },
                    onPasteFilters = { AudioMixer.pasteFilters(channel.id) },
                    onChangeDevice = { onChangeDevice(channel.id) },
                    modifier = Modifier.fillMaxHeight(),
                )
                if (index < visible.lastIndex) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Divider),
                    )
                }
            }
        }
    }
}

@Composable
private fun MixerToolbar(
    mix: AudioMix,
    hiddenCount: Int,
    optionsOpen: Boolean,
    onOptionsOpenChange: (Boolean) -> Unit,
    onToggleHidden: () -> Unit,
    onToggleLayout: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onAddApplication: () -> Unit,
    onUnhideAll: () -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onShowInactive: (Boolean) -> Unit,
    onKeepHiddenLast: (Boolean) -> Unit,
    onKeepInactiveLast: (Boolean) -> Unit,
) {
    Surface(color = SurfaceRaised) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$hiddenCount tersembunyi",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hiddenCount > 0) TextSecondary else TextTertiary,
                    modifier = Modifier
                        .clickable(enabled = hiddenCount > 0, onClick = onToggleHidden)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                MixerIconButton(
                    onClick = onToggleLayout,
                    description = if (mix.verticalLayout) {
                        "Tata letak horizontal"
                    } else {
                        "Tata letak vertikal"
                    },
                ) { color -> LayoutIcon(!mix.verticalLayout, color) }
                Spacer(Modifier.width(2.dp))
                MixerIconButton(
                    onClick = onOpenAdvanced,
                    description = stringResource(R.string.audio_properti_audio_lanjutan),
                ) { color -> GearIcon(color) }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.audio_opsi),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable { onOptionsOpenChange(true) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }

            DropdownMenu(
                expanded = optionsOpen,
                onDismissRequest = { onOptionsOpenChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_tambah_application_audio_capture)) },
                    onClick = {
                        onOptionsOpenChange(false)
                        onAddApplication()
                    },
                )
                HorizontalDivider(color = Divider)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_tampilkan_semua_yang_disembunyikan)) },
                    onClick = {
                        onUnhideAll()
                        onOptionsOpenChange(false)
                    },
                )
                HorizontalDivider(color = Divider)
                CheckItem("Tampilkan sumber tersembunyi", mix.showHidden, onShowHidden)
                CheckItem("Tampilkan sumber nonaktif", mix.showInactive, onShowInactive)
                CheckItem(
                    if (mix.verticalLayout) {
                        "Simpan sumber tersembunyi di kanan"
                    } else {
                        "Simpan sumber tersembunyi di bawah"
                    },
                    mix.keepHiddenLast,
                    onKeepHiddenLast,
                )
                CheckItem(
                    if (mix.verticalLayout) {
                        "Simpan sumber nonaktif di kanan"
                    } else {
                        "Simpan sumber nonaktif di bawah"
                    },
                    mix.keepInactiveLast,
                    onKeepInactiveLast,
                )
                HorizontalDivider(color = Divider)
                DropdownMenuItem(
                    text = {
                        Text(
                            if (mix.verticalLayout) "Tata letak horizontal" else "Tata letak vertikal",
                        )
                    },
                    onClick = {
                        onToggleLayout()
                        onOptionsOpenChange(false)
                    },
                )
                HorizontalDivider(color = Divider)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_properti_audio_lanjutan_2)) },
                    onClick = {
                        onOptionsOpenChange(false)
                        onOpenAdvanced()
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Text(
                text = if (checked) "✓" else " ",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun AudioHeaderAction(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.height(34.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

private fun rankedChannels(mix: AudioMix, activeIds: Set<AudioChannelId>): List<AudioChannel> {
    val visible = mix.channels.filter { channel ->
        when {
            channel.pinned -> true
            channel.hidden -> mix.showHidden
            channel.id in activeIds -> true
            else -> mix.showInactive
        }
    }
    return visible.sortedWith(
        compareBy(
            { channel ->
                var weight = if (channel.pinned) 20 else 40
                if (channel.hidden && mix.keepHiddenLast) weight += 5
                if (channel.id !in activeIds && mix.keepInactiveLast) weight += 5
                weight
            },
            { it.order },
            { it.name.lowercase() },
        ),
    )
}

private fun mixerSubtitle(state: RecordingState, mix: AudioMix, ready: Int): String = when {
    !mix.masterEnabled -> "Rekam audio dimatikan • mixer tetap aktif"
    state is RecordingState.Preparing -> "Menyiapkan sesi tangkap layar…"
    state is RecordingState.Previewing -> "Pratinjau • $ready kanal audio aktif"
    state is RecordingState.Recording -> "Merekam • $ready kanal masuk trek audio"
    state is RecordingState.Idle ->
        "Mixer gaya OBS • ${mix.enabledChannels().size} dari ${mix.channels.size} kanal siap"

    else -> "Mixer gaya OBS • $ready dari ${mix.channels.size} kanal aktif"
}
