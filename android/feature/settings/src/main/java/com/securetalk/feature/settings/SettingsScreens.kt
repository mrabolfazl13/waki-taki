package com.securetalk.feature.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.database.data.SettingsRepository
import com.securetalk.core.design.StColors
import com.securetalk.core.network.impl.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    val connectionMonitor: ConnectionMonitor,
) : ViewModel() {

    val settingsFlow: StateFlow<SettingsRepository.Settings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.Settings())

    val session: StateFlow<com.securetalk.core.network.impl.SessionInfo> = auth.session

    fun setDisplayName(name: String) = viewModelScope.launch { settings.setDisplayName(name) }
    fun setApiBaseUrl(url: String) = viewModelScope.launch { settings.setApiBaseUrl(url) }
    fun setDefaultSpeaker(value: Boolean) = viewModelScope.launch { settings.setDefaultSpeaker(value) }
    fun setDiagnostics(value: Boolean) = viewModelScope.launch { settings.setDiagnosticsEnabled(value) }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            onDone()
        }
    }
}

@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onGoDevices: () -> Unit,
    onGoDiagnostics: () -> Unit,
    onGoSecurity: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settingsFlow.collectAsState()
    var name by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var url by remember(settings.apiBaseUrl) { mutableStateOf(settings.apiBaseUrl) }
    val connection by viewModel.connectionMonitor.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = { viewModel.setDisplayName(name) }) { Text("SAVE NAME") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SERVER", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("API base URL (/api/v1)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = { viewModel.setApiBaseUrl(url) }) { Text("SAVE SERVER") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Default to speaker on PTT", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.defaultSpeaker, onCheckedChange = viewModel::setDefaultSpeaker)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostics collection", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.diagnosticsEnabled, onCheckedChange = viewModel::setDiagnostics)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGoDevices, modifier = Modifier.weight(1f)) { Text("DEVICES") }
            Button(onClick = onGoDiagnostics, modifier = Modifier.weight(1f)) { Text("DIAGNOSTICS") }
            Button(onClick = onGoSecurity, modifier = Modifier.weight(1f)) { Text("SECURITY") }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.logout(onLogout) },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = StColors.TxRed),
        ) { Text("LOG OUT") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Connection: ${connection.name.lowercase()}",
            style = MaterialTheme.typography.labelSmall,
            color = if (connection == ConnectionState.ONLINE_CONNECTED) StColors.Green else StColors.GreyText,
        )
    }
}

@Composable
fun AudioSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settingsFlow.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Audio", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CODEC", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Opus via MediaCodec on Android 10+ (API 29). Devices on API 26-28 use the " +
                        "AAC-LC MediaCodec fallback — channels pick one codec per epoch.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text("AUDIO PATH", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Capture uses the VOICE_COMMUNICATION audio path (platform echo cancellation " +
                        "and noise suppression), 48 kHz mono, 20 ms frames. Playback uses the " +
                        "low-latency AudioTrack with an adaptive jitter buffer (max 400 ms) and " +
                        "silence-based packet-loss concealment.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Default to speaker", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = settings.defaultSpeaker, onCheckedChange = viewModel::setDefaultSpeaker)
        }
    }
}
