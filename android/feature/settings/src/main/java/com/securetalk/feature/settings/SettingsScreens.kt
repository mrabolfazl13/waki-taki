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
import androidx.compose.material3.MaterialTheme
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
import com.securetalk.core.design.StAvatar
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StChip
import com.securetalk.core.design.StColors
import com.securetalk.core.design.StSectionLabel
import com.securetalk.core.design.StSwitch
import com.securetalk.core.design.StTextField
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.stTokens
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

/** Settings: identity, account, network, audio and privacy in tidy sections. */
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
    val session by viewModel.session.collectAsState()
    val t = stTokens()
    val online = connection == ConnectionState.ONLINE_CONNECTED

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StTopBar(title = "Settings")

        // ---- identity ------------------------------------------------------------
        StCard(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            accent = if (online) t.emerald else t.txRed,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StAvatar(session.displayName.ifBlank { settings.displayName }, size = 46.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        session.displayName.ifBlank { settings.displayName.ifBlank { "Not signed in" } },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "connection · ${connection.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (online) t.emerald else t.greyText,
                    )
                }
            }
        }

        StSectionLabel("ACCOUNT")
        StCard(Modifier.padding(horizontal = 16.dp)) {
            StTextField(value = name, onValueChange = { name = it }, label = "Display name")
            Spacer(Modifier.height(10.dp))
            StButton("SAVE NAME", { viewModel.setDisplayName(name) }, compact = true)
        }

        StSectionLabel("NETWORK")
        StCard(Modifier.padding(horizontal = 16.dp)) {
            StTextField(value = url, onValueChange = { url = it }, label = "API base URL (/api/v1)")
            Spacer(Modifier.height(10.dp))
            StButton("SAVE SERVER", { viewModel.setApiBaseUrl(url) }, compact = true)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StButton("DEVICES", onGoDevices, style = StButtonStyle.Ghost, compact = true)
            StButton("DIAGNOSTICS", onGoDiagnostics, style = StButtonStyle.Ghost, compact = true)
            StButton("SECURITY", onGoSecurity, style = StButtonStyle.Ghost, compact = true)
        }

        StSectionLabel("AUDIO & PRIVACY")
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Default to speaker on PTT", style = MaterialTheme.typography.bodyLarge)
                    Text("Route transmission to the loudspeaker", style = MaterialTheme.typography.labelSmall, color = t.greyText)
                }
                StSwitch(checked = settings.defaultSpeaker, onCheckedChange = viewModel::setDefaultSpeaker)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostics collection", style = MaterialTheme.typography.bodyLarge)
                    Text("Record connection transitions locally", style = MaterialTheme.typography.labelSmall, color = t.greyText)
                }
                StSwitch(checked = settings.diagnosticsEnabled, onCheckedChange = viewModel::setDiagnostics)
            }
        }

        Spacer(Modifier.height(16.dp))
        StButton(
            "LOG OUT",
            { viewModel.logout(onLogout) },
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            style = StButtonStyle.Danger,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Audio settings: codec + audio path documentation and speaker toggle. */
@Composable
fun AudioSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settingsFlow.collectAsState()
    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StTopBar(title = "Audio")
        StSectionLabel("CODEC")
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.teal) {
            Text(
                "Opus via MediaCodec on Android 10+ (API 29). Devices on API 26-28 use the " +
                    "AAC-LC MediaCodec fallback — channels pick one codec per epoch.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StSectionLabel("AUDIO PATH")
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.emerald) {
            Text(
                "Capture uses the VOICE_COMMUNICATION audio path (platform echo cancellation " +
                    "and noise suppression), 48 kHz mono, 20 ms frames. Playback uses the " +
                    "low-latency AudioTrack with an adaptive jitter buffer (max 400 ms) and " +
                    "silence-based packet-loss concealment.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StSectionLabel("OUTPUT")
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Default to speaker", style = MaterialTheme.typography.bodyLarge)
                StSwitch(checked = settings.defaultSpeaker, onCheckedChange = viewModel::setDefaultSpeaker)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
