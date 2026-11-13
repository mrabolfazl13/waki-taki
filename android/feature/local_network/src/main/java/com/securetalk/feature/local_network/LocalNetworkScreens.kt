package com.securetalk.feature.local_network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.securetalk.core.design.StColors
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Offline host", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This device becomes the local host: it runs an embedded Ktor server on port " +
                "53317, advertises `_securetalk._tcp` via NSD, relays opaque encrypted packets " +
                "and coordinates floor control. It CANNOT decrypt participants' audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = StColors.GreyText,
        )
        Spacer(Modifier.height(24.dp))
        if (!running) {
            Button(onClick = {
                viewModel.startHost()
                onStartService()
            }, modifier = Modifier.fillMaxWidth()) { Text("START HOST") }
        } else {
            Button(onClick = {
                viewModel.stopHost()
                onStopService()
            }, modifier = Modifier.fillMaxWidth()) { Text("STOP HOST") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGoHotspot, modifier = Modifier.weight(1f)) { Text("HOTSPOT GUIDE") }
            OutlinedButton(onClick = onGoJoin, modifier = Modifier.weight(1f)) { Text("JOIN HOST") }
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("WHAT THE HOST DOES", style = MaterialTheme.typography.labelLarge)
                Text(
                    "• Routes ciphertext envelopes by recipient device ID\n" +
                        "• Enforces the floor protocol (single speaker, 60 s lease, queue)\n" +
                        "• Fans out binary media frames unchanged\n" +
                        "• Holds no group keys — no media decryption possible",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Hotspot setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "SecureTalk cannot toggle your hotspot itself (Android does not allow it). " +
                "Follow these steps, then come back — peers discover the host automatically via NSD.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("1.", style = MaterialTheme.typography.labelLarge)
                Text("Open Android Settings → Network & internet → Hotspot & tethering (button below).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("2.", style = MaterialTheme.typography.labelLarge)
                Text("Turn the Wi-Fi hotspot ON. Note the SSID and password.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("3.", style = MaterialTheme.typography.labelLarge)
                Text("Connect every other member's phone to that hotspot (keep this host device connected too).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("4.", style = MaterialTheme.typography.labelLarge)
                Text("Return here and run PROBE — devices advertising _securetalk._tcp appear within seconds.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("OPEN WIRELESS SETTINGS") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("BACK") }
        Spacer(Modifier.height(16.dp))
        val hosts by viewModel.hosts.collectAsState()
        if (hosts.isNotEmpty()) {
            Text("Discovered hosts:", style = MaterialTheme.typography.labelLarge)
            hosts.forEach {
                Text("${it.hostName} — ${it.address}:${it.port}", color = StColors.Teal)
            }
        }
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Join offline network", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Button(onClick = viewModel::probe, modifier = Modifier.fillMaxWidth()) {
            Text("SEARCH FOR HOSTS (NSD)")
        }
        Spacer(Modifier.height(12.dp))
        hosts.forEach { host ->
            Card(Modifier.fillMaxWidth(), onClick = { viewModel.connectTo(host) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(host.hostName, style = MaterialTheme.typography.titleMedium)
                    Text("${host.address}:${host.port}", style = MaterialTheme.typography.labelSmall, color = StColors.Teal)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            label = { Text("Invite QR payload") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.joinByQrPayload(payload) },
            enabled = payload.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("JOIN WITH PAYLOAD") }

        Spacer(Modifier.height(16.dp))
        Text("Manual fallback", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it },
                label = { Text("Host IP") },
                modifier = Modifier.weight(2f),
                singleLine = true,
            )
            OutlinedTextField(
                value = manualPort,
                onValueChange = { manualPort = it },
                label = { Text("Port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val port = manualPort.toIntOrNull() ?: 53317
                viewModel.connectTo(LocalHostInfo("manual", manualHost.trim(), port))
            },
            enabled = manualHost.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("CONNECT MANUALLY") }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = StColors.TxRed)
        }
    }

    androidx.compose.runtime.LaunchedEffect(joined) {
        if (joined == true) onJoined()
    }
}
