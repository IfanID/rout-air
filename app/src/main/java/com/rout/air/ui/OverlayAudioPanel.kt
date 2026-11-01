package com.rout.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rout.air.data.AudioMixer

/**
 * Panel audio overlay: susunan sama seperti baris kanal di halaman Audio
 * (nama + status, tombol mute di kiri, fader di atas meter), versi ringkas
 * tanpa angka.
 */
@Composable
internal fun OverlayAudioPanel() {
    val mix by AudioMixer.mix.collectAsState()
    val levels by AudioMixer.levels.collectAsState()
    val active = mix.channels.filter { it.enabled }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Sakelar induk, sama seperti "Rekam audio" di halaman Audio.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MixerIconButton(
                onClick = { AudioMixer.setMasterEnabled(!mix.masterEnabled) },
                description = if (mix.masterEnabled) "Matikan audio" else "Aktifkan audio",
                active = !mix.masterEnabled,
                activeColor = Color(0xFFFF4C4C),
            ) { color -> SpeakerIcon(!mix.masterEnabled, color, 14.dp) }
            Text(
                text = if (mix.masterEnabled) "Audio aktif" else "Audio mati",
                color = if (mix.masterEnabled) Color.White else Color(0xFFFF8A8A),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (active.isEmpty()) {
            Text(
                text = "Tidak ada audio aktif",
                color = Color(0xFFB8C2CC),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        active.forEach { channel ->
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        color = if (channel.enabled) Color.White else Color(0xFF8A94A0),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(categoryFor(channel, channel.enabled))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MixerIconButton(
                        onClick = {
                            val current = AudioMixer.mix.value.channel(channel.id)
                            AudioMixer.update(current.copy(muted = !current.muted))
                        },
                        description = if (channel.muted) "Aktifkan suara" else "Bisukan",
                        active = channel.muted,
                        activeColor = Color(0xFFFF4C4C),
                    ) { color -> SpeakerIcon(channel.muted, color, 14.dp) }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        VolumeFader(
                            gainDb = channel.gainDb,
                            enabled = !channel.locked,
                            vertical = false,
                            onGainChange = {
                                AudioMixer.update(
                                    AudioMixer.mix.value.channel(channel.id).copy(gainDb = it),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        VolumeMeter(
                            level = levels.forChannel(channel.id),
                            active = channel.enabled && !channel.muted,
                            decay = mix.meterDecay,
                            vertical = false,
                            modifier = Modifier.fillMaxWidth(),
                            showScale = false,
                            muted = channel.muted,
                        )
                    }
                }
            }
        }
    }
}
