package com.securetalk.feature.local_network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.model.LocalHostInfo
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StColors
import com.securetalk.core.design.StSectionLabel
import com.securetalk.core.design.StTextField
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.stTokens
import com.securetalk.core.network.impl.QrPayload
import com.securetalk.core.network.monitor.ConnectionMonitorImpl
import com.securetalk.core.network.nsd.NsdDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalNetworkViewModel @Inject constructor(
    private val connectionMonitor: ConnectionMonitor,
    private val channelManager: ChannelManager,
) : ViewModel() {

    val hosts: StateFlow<List<LocalHostInfo>> = connectionMonitor.localHosts
    val state = connectionMonitor.state

    private val _hostRunning = MutableStateFlow(false)
    val hostRunning: StateFlow<Boolean> = _hostRunning.asStateFlow()

    private val _joined = MutableStateFlow<Boolean?>(null)
    val joined: StateFlow<Boolean?> = _joined.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun startHost() {
        _hostRunning.value = true
        _error.value = null
    }

    fun stopHost() {
        _hostRunning.value = false
    }

    fun probe() {
        viewModelScope.launch {
            connectionMonitor.probeLocalHosts()
        }
    }

    fun connectTo(host: LocalHostInfo) {
        viewModelScope.launch {
            connectionMonitor.confirmSwitchToLocal(host)
                .onSuccess { _joined.value = true }
                .onFailure { _error.value = it.message ?: "Connection failed" }
        }
    }

    fun joinByQrPayload(payload: String) {
        viewModelScope.launch {
            val parsed = QrPayload.parse(payload)
                ?: return@launch run { _error.value = "Not a SecureTalk invite payload" }
            channelManager.joinChannel(payload)
                .onSuccess { _joined.value = true }
                .onFailure { _error.value = it.message ?: "Join failed" }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

/** Host setup: start/stop the embedded local host service. */
@Composable
fun HostSetupScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoHotspot: () -> Unit = {},
    onGoJoin: () -> Unit = {},
    viewModel: LocalNetworkViewModel = hiltViewModel(),
) {
    val running by viewModel.hostRunning.collectAsState()
    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StTopBar(title = "Offline host")
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.teal) {
            Text(
                "This device becomes the local host: it runs an embedded Ktor server on port " +
                    "53317, advertises `_securetalk._tcp` via NSD, relays opaque encrypted packets " +
                    "and coordinates floor control. It CANNOT decrypt participants' audio.",
                style = MaterialTheme.typography.bodyMedium,
                color = t.greyText,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (!running) {
            StButton("START HOST", {
                viewModel.startHost()
                onStartService()
            }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        } else {
            StButton("STOP HOST", {
                viewModel.stopHost()
                onStopService()
            }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), style = StButtonStyle.Danger)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StButton("HOTSPOT GUIDE", onGoHotspot, Modifier.weight(1f), style = StButtonStyle.Ghost, compact = true)
            StButton("JOIN HOST", onGoJoin, Modifier.weight(1f), style = StButtonStyle.Ghost, compact = true)
        }
        Spacer(Modifier.height(14.dp))
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Text("WHAT THE HOST DOES", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Spacer(Modifier.height(4.dp))
            Text(
                "• Routes ciphertext envelopes by recipient device ID\n" +
                    "• Enforces the floor protocol (single speaker, 60 s lease, queue)\n" +
                    "• Fans out binary media frames unchanged\n" +
                    "• Holds no group keys — no media decryption possible",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Guided hotspot setup (spec: never fake hotspot control — we open the real
 * Android settings and give precise manual steps).
 */
@Composable
fun HotspotGuideScreen(
    onBack: () -> Unit = {},
    viewModel: LocalNetworkViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StTopBar(title = "Hotspot setup", onBack = onBack)
        StCard(Modifier.padding(horizontal = 16.dp), accent = t.amber) {
            Text(
                "SecureTalk cannot toggle your hotspot itself (Android does not allow it). " +
                    "Follow these steps, then come back — peers discover the host automatically via NSD.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Text("1.", style = MaterialTheme.typography.labelLarge, color = t.emerald)
            Text("Open Android Settings → Network & internet → Hotspot & tethering (button below).", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("2.", style = MaterialTheme.typography.labelLarge, color = t.emerald)
            Text("Turn the Wi-Fi hotspot ON. Note the SSID and password.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("3.", style = MaterialTheme.typography.labelLarge, color = t.emerald)
            Text("Connect every other member's phone to that hotspot (keep this host device connected too).", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("4.", style = MaterialTheme.typography.labelLarge, color = t.emerald)
            Text("Return here and run PROBE — devices advertising _securetalk._tcp appear within seconds.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        StButton("OPEN WIRELESS SETTINGS", {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            runCatching { context.startActivity(intent) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), style = StButtonStyle.Ghost)
        val hosts by viewModel.hosts.collectAsState()
        if (hosts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            StCard(Modifier.padding(horizontal = 16.dp), accent = t.teal) {
                Text("DISCOVERED HOSTS", style = MaterialTheme.typography.labelLarge, color = t.teal)
                hosts.forEach {
                    Text("${it.hostName} — ${it.address}:${it.port}", color = t.teal)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Join local network: NSD probe, QR payload entry, manual address fallback. */
@Composable
fun LocalJoinScreen(
    onJoined: () -> Unit,
    viewModel: LocalNetworkViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hosts.collectAsState()
    var payload by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("53317") }
    val error by viewModel.error.collectAsState()
    val joined by viewModel.joined.collectAsState()

    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StTopBar(title = "Join offline network")
        Spacer(Modifier.height(4.dp))
        StButton("SEARCH FOR HOSTS (NSD)", viewModel::probe, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), style = StButtonStyle.Ghost)
        Spacer(Modifier.height(10.dp))
        hosts.forEach { host ->
            StCard(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                accent = t.teal,
                onClick = { viewModel.connectTo(host) },
            ) {
                Text(host.hostName, style = MaterialTheme.typography.titleMedium)
                Text("${host.address}:${host.port}", style = MaterialTheme.typography.labelSmall, color = t.teal)
            }
        }

        Spacer(Modifier.height(14.dp))
        StCard(Modifier.padding(horizontal = 16.dp)) {
            StTextField(
                value = payload,
                onValueChange = { payload = it },
                label = "Invite QR payload",
            )
            Spacer(Modifier.height(10.dp))
            StButton(
                "JOIN WITH PAYLOAD",
                { viewModel.joinByQrPayload(payload) },
                enabled = payload.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        StSectionLabel("MANUAL FALLBACK")
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = "Host IP",
                    modifier = Modifier.weight(2f),
                )
                StTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    label = "Port",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            StButton(
                "CONNECT MANUALLY",
                {
                    val port = manualPort.toIntOrNull() ?: 53317
                    viewModel.connectTo(LocalHostInfo("manual", manualHost.trim(), port))
                },
                enabled = manualHost.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                style = StButtonStyle.Ghost,
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = t.txRed, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(16.dp))
    }

    androidx.compose.runtime.LaunchedEffect(joined) {
        if (joined == true) onJoined()
    }
}
