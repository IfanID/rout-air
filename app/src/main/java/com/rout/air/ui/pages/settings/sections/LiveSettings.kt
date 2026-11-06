package com.rout.air.ui.pages.settings.sections

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rout.air.core.model.LiveConfig
import com.rout.air.core.model.LiveService
import com.rout.air.live.rtmp.RtmpTester
import com.rout.air.live.rtmp.RtmpTestResult
import com.rout.air.live.youtube.GoogleAuth
import com.rout.air.live.youtube.GoogleAuthStore
import com.rout.air.live.youtube.GoogleTokens
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import androidx.activity.result.IntentSenderRequest
import com.rout.air.live.youtube.YouTubeApi
import androidx.compose.ui.res.stringResource
import com.rout.air.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rout.air.ui.components.Hint
import com.rout.air.ui.pages.settings.components.SectionTitle
import com.rout.air.ui.pages.settings.components.SettingSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveSettings(
    live: LiveConfig,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    var serviceExpanded by remember { mutableStateOf(false) }
    var showingKey by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    // Default seperti OBS: hubungkan akun, bukan isi kunci manual.
    var manualKey by remember {
        mutableStateOf(!live.accountLinked && live.streamKey.isNotBlank())
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<RtmpTestResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SectionTitle(stringResource(R.string.settings_live_destination))
    ExposedDropdownMenuBox(
        expanded = serviceExpanded,
        onExpandedChange = { serviceExpanded = it },
    ) {
        OutlinedTextField(
            value = live.service.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_live_service)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = serviceExpanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = serviceExpanded,
            onDismissRequest = { serviceExpanded = false },
        ) {
            LiveService.entries.forEach { service ->
                DropdownMenuItem(
                    text = { Text(service.label) },
                    onClick = {
                        serviceExpanded = false
                        onLiveConfigChange(
                            live.copy(
                                service = service,
                                serverUrl = if (service.custom) {
                                    live.serverUrl
                                } else {
                                    service.defaultServer
                                },
                            ),
                        )
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    if (live.service == LiveService.YouTube) {
        // Sama seperti OBS: pilih hubungkan akun atau isi stream key manual.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !manualKey,
                onClick = { manualKey = false },
                label = { Text(stringResource(R.string.settings_live_connect_account)) },
            )
            FilterChip(
                selected = manualKey,
                onClick = { manualKey = true },
                label = { Text(stringResource(R.string.settings_live_use_key)) },
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    if (live.service == LiveService.YouTube && !manualKey) {
        YoutubeAccountSection(live, onLiveConfigChange)
    } else {
        OutlinedTextField(
            value = live.serverUrl,
            onValueChange = { onLiveConfigChange(live.copy(serverUrl = it)) },
            label = { Text(stringResource(R.string.settings_live_server_url)) },
            placeholder = { Text(stringResource(R.string.settings_live_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = live.streamKey,
            onValueChange = {
                // Mengetik kunci manual berarti keluar dari mode akun.
                onLiveConfigChange(live.copy(streamKey = it, accountLinked = false))
            },
            label = { Text(stringResource(R.string.settings_live_key)) },
            visualTransformation = if (showingKey) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { showingKey = !showingKey }) {
                    Text(
                        stringResource(
                            if (showingKey) R.string.settings_live_hide
                            else R.string.settings_live_show,
                        ),
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(12.dp))
    if (live.service.custom) {
        SettingSwitchRow(
            title = stringResource(R.string.settings_live_use_auth),
            subtitle = stringResource(R.string.settings_live_use_auth_desc),
            checked = live.useAuth,
        ) { onLiveConfigChange(live.copy(useAuth = it)) }

        if (live.useAuth) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = live.username,
                onValueChange = { onLiveConfigChange(live.copy(username = it)) },
                label = { Text(stringResource(R.string.settings_live_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = live.password,
                onValueChange = { onLiveConfigChange(live.copy(password = it)) },
                label = { Text(stringResource(R.string.settings_live_password)) },
                visualTransformation = if (showPassword) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            stringResource(
                                if (showPassword) R.string.settings_live_hide
                                else R.string.settings_live_show,
                            ),
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { RtmpTester.test(live) }
                    testResult = result
                    testing = false
                }
            },
            enabled = !testing && live.isConfigured,
        ) {
            if (testing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.settings_live_testing))
            } else {
                Text(stringResource(R.string.settings_live_test))
            }
        }
        testResult?.let { result ->
            Text(
                text = if (result.ok) {
                    stringResource(R.string.settings_live_test_ok, result.latencyMs)
                } else {
                    stringResource(R.string.settings_live_test_fail, result.message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SettingSwitchRow(
        title = stringResource(R.string.settings_live_reconnect),
        subtitle = stringResource(R.string.settings_live_reconnect_desc),
        checked = live.autoReconnect,
    ) { onLiveConfigChange(live.copy(autoReconnect = it)) }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.settings_live_audio_track))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (track in 1..6) {
            FilterChip(
                selected = live.audioTrack == track,
                onClick = { onLiveConfigChange(live.copy(audioTrack = track)) },
                label = { Text("$track") },
            )
        }
    }

    if (live.service.dashboardUrl.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(live.service.dashboardUrl)),
                    )
                }
            },
        ) {
            Text(stringResource(R.string.settings_live_open_dashboard))
        }
    }

    Spacer(Modifier.height(12.dp))
    Hint(stringResource(R.string.settings_live_url_hint))
    Hint(stringResource(R.string.settings_live_notes))

}

/**
 * Bagian "Hubungkan Akun" YouTube, meniru OBS: login sekali lewat browser,
 * lalu server dan stream key diisi otomatis dan chat memakai akun yang sama.
 */
@Composable
internal fun YoutubeAccountSection(
    live: LiveConfig,
    onLiveConfigChange: (LiveConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GoogleAuthStore(context) }
    var email by remember { mutableStateOf(if (store.loggedIn) store.accountEmail else "") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Setelah token didapat: simpan, ambil email, lalu isi server + stream key
    // seperti tombol Connect Account di OBS.
    suspend fun finishLogin(accessToken: String) {
        store.saveTokens(GoogleTokens(accessToken, GoogleAuth.expiryFrom()))
        val mail = withContext(Dispatchers.IO) { GoogleAuth.accountEmail(accessToken) }
        store.accountEmail = mail
        email = mail
        val target = withContext(Dispatchers.IO) {
            runCatching { YouTubeApi(accessToken).streamTarget() }.getOrNull()
        }
        if (target == null) {
            status = context.getString(R.string.settings_google_no_key)
        } else {
            onLiveConfigChange(
                live.copy(
                    serverUrl = target.server,
                    streamKey = target.key,
                    accountLinked = true,
                ),
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            status = context.getString(R.string.settings_login_cancelled)
            busy = false
            return@rememberLauncherForActivityResult
        }
        val authorization = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (error: ApiException) {
            status = error.message ?: context.getString(R.string.settings_login_failed)
            busy = false
            return@rememberLauncherForActivityResult
        }
        val token = authorization.accessToken
        if (token.isNullOrBlank()) {
            status = context.getString(R.string.settings_login_no_token)
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            finishLogin(token)
            busy = false
        }
    }

    fun authorize() {
        status = ""
        busy = true
        Identity.getAuthorizationClient(context)
            .authorize(GoogleAuth.authorizationRequest())
            .addOnSuccessListener { authorization ->
                val pending = authorization.pendingIntent
                if (authorization.hasResolution() && pending != null) {
                    launcher.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build(),
                    )
                    return@addOnSuccessListener
                }
                val token = authorization.accessToken
                if (token.isNullOrBlank()) {
                    status = context.getString(R.string.settings_login_no_token)
                    busy = false
                } else {
                    scope.launch {
                        finishLogin(token)
                        busy = false
                    }
                }
            }
            .addOnFailureListener { error ->
                status = error.message ?: context.getString(R.string.settings_login_failed)
                busy = false
            }
    }

    if (email.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_google_connected_as),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(email, style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            // Reset akun default saja; izin lama tetap ada.
                            GoogleAuth.switchAccount(context)
                            store.clear()
                            email = ""
                            status = ""
                            onLiveConfigChange(
                                live.copy(
                                    serverUrl = "",
                                    streamKey = "",
                                    accountLinked = false,
                                ),
                            )
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_google_switch))
                }
                TextButton(
                    onClick = {
                        store.clear()
                        email = ""
                        status = ""
                        onLiveConfigChange(
                            live.copy(serverUrl = "", streamKey = "", accountLinked = false),
                        )
                    },
                ) {
                    Text(stringResource(R.string.settings_google_disconnect))
                }
            }
        }
    } else if (!GoogleAuth.available(context)) {
        // Tanpa Google Play Services tombol tidak akan pernah berhasil.
        Hint(stringResource(R.string.settings_google_not_configured))
    } else {
        Button(
            enabled = !busy,
            onClick = { authorize() },
        ) {
            Text(
                if (busy) {
                    stringResource(R.string.settings_google_waiting_browser)
                } else {
                    stringResource(R.string.settings_google_connect)
                },
            )
        }
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (email.isBlank()) Hint(stringResource(R.string.settings_google_hint))
}
