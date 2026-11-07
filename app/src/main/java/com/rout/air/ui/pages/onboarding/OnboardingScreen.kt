package com.rout.air.ui.pages.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rout.air.R
import com.rout.air.data.settings.SettingsStore
import com.rout.air.ui.dialogs.AutoConfigWizardDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pengenalan sekali pakai saat aplikasi pertama dibuka.
 *
 * Semua izin yang dibutuhkan diminta di sini—runtime (mic, kamera,
 * notifikasi, Bluetooth), izin khusus sistem (overlay, bebas optimasi
 * baterai), dan setup otomatis—supaya saat pengguna menekan Rekam pertama
 * kali hanya dialog tangkap layar yang muncul.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore(context) }

    var step by remember { mutableIntStateOf(0) }
    var config by remember { mutableStateOf(store.load()) }
    val live = remember { store.loadLive() }
    var wizardOpen by remember { mutableStateOf(false) }
    var wizardDone by remember { mutableStateOf(store.isWizardDone()) }
    var wizardResult by remember { mutableStateOf(store.lastAutoConfig()) }

    // Status izin disegarkan setiap aplikasi kembali terlihat (mis. sepulang
    // dari halaman Setelan sistem untuk izin overlay/baterai).
    var runtimeGranted by remember { mutableStateOf(runtimePermissionsGranted(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(ignoringBatteryOptimizations(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        runtimeGranted = runtimePermissionsGranted(context)
    }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryExempt = ignoringBatteryOptimizations(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                runtimeGranted = runtimePermissionsGranted(context)
                overlayGranted = Settings.canDrawOverlays(context)
                batteryExempt = ignoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val steps = listOf(
        Step(
            symbol = "◫",
            title = R.string.onboarding_1_judul,
            body = R.string.onboarding_1_isi,
        ),
        Step(
            symbol = "◎",
            title = R.string.onboarding_2_judul,
            body = R.string.onboarding_2_isi,
        ),
        Step(
            symbol = "⚙",
            title = R.string.onboarding_3_judul,
            body = R.string.onboarding_3_isi,
        ),
        Step(
            symbol = "⏱",
            title = R.string.onboarding_4_judul,
            body = R.string.onboarding_4_isi,
        ),
        Step(
            symbol = "▶",
            title = R.string.onboarding_5_judul,
            body = R.string.onboarding_5_isi,
        ),
    )
    val current = steps[step]
    val lastStep = step == steps.lastIndex
    // Tombol Lanjut hanya aktif setelah langkah ini benar-benar selesai.
    val canContinue = when (step) {
        1 -> runtimeGranted
        2 -> overlayGranted && batteryExempt
        3 -> wizardResult != null
        else -> true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.onboarding_lewati))
            }
        }

        Text(
            text = stringResource(R.string.onboarding_step, step + 1, steps.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        LinearProgressIndicator(
            progress = { (step + 1f) / steps.size },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(4.dp)),
        )

        // Konten di tengah layar bila pendek; tetap bisa digulir bila
        // kartu izin melebihi tinggi layar.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = current.symbol,
                        fontSize = 38.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    text = stringResource(current.title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(current.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                when (step) {
                    1 -> RuntimePermissionStep(
                        allGranted = runtimeGranted,
                        onRequest = { permissionLauncher.launch(runtimePermissions().toTypedArray()) },
                    )

                    2 -> SpecialPermissionStep(
                        overlayGranted = overlayGranted,
                        batteryExempt = batteryExempt,
                        onOverlay = {
                            overlayLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                        onBattery = {
                            batteryLauncher.launch(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    )

                    3 -> AutoConfigStep(
                        done = wizardResult != null,
                        result = wizardResult,
                        onRun = { wizardOpen = true },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            steps.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == step) 20.dp else 8.dp, 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }

        if (!canContinue) {
            Text(
                text = stringResource(R.string.onboarding_step_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Button(
            enabled = canContinue,
            onClick = {
                if (lastStep) {
                    // Wizard dianggap selesai walau dilewati: pengaturan
                    // otomatis tetap bisa dijalankan ulang dari Setelan.
                    store.setWizardDone()
                    store.setOnboardingDone()
                    onDone()
                } else {
                    step++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (lastStep) R.string.onboarding_mulai else R.string.onboarding_lanjut,
                ),
            )
        }
    }

    if (wizardOpen) {
        AutoConfigWizardDialog(
            config = config,
            live = live,
            onApply = { updated ->
                config = updated
                store.save(updated)
                store.setWizardDone()
                val summary = context.getString(
                    R.string.onboarding_wizard_result,
                    updated.outputWidth,
                    updated.outputHeight,
                    updated.fps,
                    updated.videoCodec.label,
                    updated.videoBitrate / 1_000_000,
                )
                store.setLastAutoConfig(summary)
                wizardResult = summary
                wizardDone = true
                wizardOpen = false
            },
            onDismiss = {
                // Ditutup tanpa menerapkan: tetap bisa diulang dari Setelan.
                store.setWizardDone()
                wizardDone = true
                wizardOpen = false
            },
        )
    }
}

/** Satu halaman pengenalan. */
private data class Step(
    val symbol: String,
    val title: Int,
    val body: Int,
)

/** Daftar izin runtime yang diminta sekaligus. */
private fun runtimePermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun permissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun runtimePermissionsGranted(context: Context): Boolean =
    runtimePermissions().all { permissionGranted(context, it) }

private fun ignoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** Langkah 2: izin runtime dengan status per baris. */
@Composable
private fun RuntimePermissionStep(allGranted: Boolean, onRequest: () -> Unit) {
    val context = LocalContext.current
    PermissionCard(
        items = listOf(
            PermissionItem(
                symbol = "♪",
                title = stringResource(R.string.onboarding_perm_mic),
                detail = stringResource(R.string.onboarding_perm_mic_desc),
                enabled = permissionGranted(context, Manifest.permission.RECORD_AUDIO),
            ),
            PermissionItem(
                symbol = "✉",
                title = stringResource(R.string.onboarding_perm_notif),
                detail = stringResource(R.string.onboarding_perm_notif_desc),
                enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    permissionGranted(context, Manifest.permission.POST_NOTIFICATIONS),
            ),
            PermissionItem(
                symbol = "⌘",
                title = stringResource(R.string.onboarding_perm_bluetooth),
                detail = stringResource(R.string.onboarding_perm_bluetooth_desc),
                enabled = permissionGranted(context, Manifest.permission.BLUETOOTH_CONNECT),
            ),
        ),
        footer = if (allGranted) {
            stringResource(R.string.onboarding_perm_all_granted)
        } else {
            stringResource(R.string.onboarding_perm_hint)
        },
        actionLabel = stringResource(
            if (allGranted) R.string.onboarding_perm_granted else R.string.onboarding_perm_grant,
        ),
        actionEnabled = !allGranted,
        onAction = onRequest,
    )
}

/** Langkah 3: izin khusus yang dibuka lewat Setelan sistem. */
@Composable
private fun SpecialPermissionStep(
    overlayGranted: Boolean,
    batteryExempt: Boolean,
    onOverlay: () -> Unit,
    onBattery: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionCard(
            items = listOf(
                PermissionItem(
                    symbol = "▤",
                    title = stringResource(R.string.onboarding_perm_overlay),
                    detail = stringResource(R.string.onboarding_perm_overlay_desc),
                    enabled = overlayGranted,
                ),
            ),
            footer = stringResource(R.string.onboarding_perm_overlay_footer),
            actionLabel = stringResource(
                if (overlayGranted) R.string.onboarding_perm_granted
                else R.string.onboarding_perm_open_settings,
            ),
            actionEnabled = !overlayGranted,
            onAction = onOverlay,
        )
        PermissionCard(
            items = listOf(
                PermissionItem(
                    symbol = "⚡",
                    title = stringResource(R.string.onboarding_perm_battery),
                    detail = stringResource(R.string.onboarding_perm_battery_desc),
                    enabled = batteryExempt,
                ),
            ),
            footer = stringResource(R.string.onboarding_perm_battery_footer),
            actionLabel = stringResource(
                if (batteryExempt) R.string.onboarding_perm_granted
                else R.string.onboarding_perm_open_settings,
            ),
            actionEnabled = !batteryExempt,
            onAction = onBattery,
        )
    }
}

/** Langkah 4: menjalankan setup otomatis sekali di awal. */
@Composable
private fun AutoConfigStep(done: Boolean, result: String?, onRun: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoLine(
                stringResource(R.string.onboarding_wizard_tests),
                enabled = null,
            )
            InfoLine(
                stringResource(R.string.onboarding_wizard_applies),
                enabled = null,
            )
            if (result != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.onboarding_wizard_last, result),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (done) R.string.onboarding_wizard_rerun
                        else R.string.onboarding_wizard_run,
                    ),
                )
            }
        }
    }
}

/** Kartu daftar izin dengan tombol aksi di bawahnya. */
@Composable
private fun PermissionCard(
    items: List<PermissionItem>,
    footer: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                InfoLine(
                    label = item.title,
                    detail = item.detail,
                    enabled = item.enabled,
                    symbol = item.symbol,
                )
            }
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionLabel)
            }
        }
    }
}

private data class PermissionItem(
    val symbol: String,
    val title: String,
    val detail: String,
    val enabled: Boolean,
)

/** Satu baris izin: simbol, nama, alasan, dan status. */
@Composable
private fun InfoLine(
    label: String,
    detail: String? = null,
    enabled: Boolean?,
    symbol: String = "•",
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (enabled == true) "✓" else symbol,
            color = if (enabled == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
