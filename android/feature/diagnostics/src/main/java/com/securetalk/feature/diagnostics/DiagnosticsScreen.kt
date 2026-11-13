package com.securetalk.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.design.StColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DiagEntry(val timestampMs: Long, val text: String)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val connectionMonitor: ConnectionMonitor,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionMonitor.state

    private val _log = MutableStateFlow<List<DiagEntry>>(emptyList())
    val log: StateFlow<List<DiagEntry>> = _log.asStateFlow()

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        viewModelScope.launch {
            connectionMonitor.transitions.collect { transition ->
                when (transition) {
                    is com.securetalk.core.common.domain.ConnectionTransition.Changed -> append(
                        "state ${transition.from.name} -> ${transition.to.name}",
                    )
                    is com.securetalk.core.common.domain.ConnectionTransition.RequiresUserDecision -> append(
                        "USER DECISION REQUIRED: ${transition.reason} (hosts: ${transition.localHosts.map { "${it.address}:${it.port}" }})",
                    )
                }
            }
        }
    }

    private fun append(text: String) {
        _log.value = listOf(DiagEntry(System.currentTimeMillis(), text)) + _log.value.take(99)
    }

    fun probeLocalHosts() {
        viewModelScope.launch {
            append("probing local hosts via NSD (${NsdProbeLabels.SERVICE_TYPE})...")
            connectionMonitor.probeLocalHosts()
            val hosts = connectionMonitor.localHosts.value
            append("discovered ${hosts.size} host(s): ${hosts.joinToString { "${it.hostName} ${it.address}:${it.port}" }}")
        }
    }
}

object NsdProbeLabels {
    const val SERVICE_TYPE = "_securetalk._tcp"
}

/** Diagnostics screen: §8 transition log + manual NSD probe + targets (§9). */
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.connectionState.collectAsState()
    val log by viewModel.log.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text(
                state.name.lowercase(),
                style = MaterialTheme.typography.labelLarge,
                color = if (state == ConnectionState.ONLINE_CONNECTED) StColors.Green else StColors.Amber,
            )
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("PERFORMANCE TARGETS (spec §9)", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Local p95 latency ≤ 250 ms · Internet ≤ 500 ms · PTT grant ≤ 300 ms.\n" +
                        "Measured values are recorded in docs/TEST_RESULTS.md; on-device " +
                        "measurement is pending (no toolchain locally — NOT VERIFIED).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StColors.GreyText,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = viewModel::probeLocalHosts, modifier = Modifier.fillMaxWidth()) {
            Text("PROBE LOCAL HOSTS (NSD)")
        }
        Spacer(Modifier.height(12.dp))
        Text("EVENT LOG", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        log.forEach { entry ->
            Text(
                "${java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))}  ${entry.text}",
                style = MaterialTheme.typography.labelSmall,
                color = StColors.GreyText,
            )
        }
        if (log.isEmpty()) {
            Text(
                "No transitions recorded yet. Connection events appear here as the network changes (§8).",
                style = MaterialTheme.typography.bodyMedium,
                color = StColors.GreyText,
            )
        }
    }
}
