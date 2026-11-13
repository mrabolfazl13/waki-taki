package com.securetalk.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.domain.ConnectionTransition
import com.securetalk.core.common.model.ChannelInfo
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.design.ConnectionBadge
import com.securetalk.core.design.StateBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val channelManager: ChannelManager,
    val connectionMonitor: ConnectionMonitor,
) : ViewModel() {

    val channels: StateFlow<List<ChannelInfo>> = channelManager.channels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ConnectionState> = connectionMonitor.state

    /** §8: pending user decision when internet is lost but local hosts exist. */
    val pendingDecision: StateFlow<ConnectionTransition.RequiresUserDecision?> =
        connectionMonitor.pendingDecision

    init {
        viewModelScope.launch { channelManager.refresh() }
        viewModelScope.launch { connectionMonitor.start() }
    }

    fun refresh() {
        viewModelScope.launch { channelManager.refresh() }
    }

    fun confirmSwitchToLocal() {
        viewModelScope.launch {
            val decision = pendingDecision.value ?: return@launch
            val host = decision.localHosts.firstOrNull() ?: return@launch
            connectionMonitor.confirmSwitchToLocal(host)
        }
    }

    fun stayOnline() {
        connectionMonitor.dismissPendingDecision()
    }
}

@Composable
fun HomeScreen(
    onOpenChannel: (String) -> Unit,
    onGoChannels: () -> Unit,
    onGoLocalNetwork: () -> Unit,
    onGoSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val decision by viewModel.pendingDecision.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("SecureTalk", style = MaterialTheme.typography.titleLarge)
            ConnectionBadge(
                state = when (connection) {
                    ConnectionState.ONLINE_CONNECTED -> "Online"
                    ConnectionState.DEGRADED -> "Degraded"
                    ConnectionState.RECONNECTING -> "Reconnecting"
                    ConnectionState.LOCAL_AVAILABLE -> "Local mode"
                    ConnectionState.OFFLINE -> "Offline"
                },
                connected = connection == ConnectionState.ONLINE_CONNECTED || connection == ConnectionState.LOCAL_AVAILABLE,
                degraded = connection == ConnectionState.DEGRADED || connection == ConnectionState.RECONNECTING,
            )
        }

        decision?.let { d ->
            Card(Modifier.padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("CONNECTION LOST", style = MaterialTheme.typography.labelLarge, color = StAmber())
                    Text(d.reason, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::confirmSwitchToLocal) { Text("SWITCH TO LOCAL") }
                        OutlinedButton(onClick = viewModel::stayOnline) { Text("STAY OFFLINE") }
                    }
                }
            }
        }

        when {
            channels.isEmpty() -> StateBlock(
                title = "No channels yet",
                detail = "Create a channel or join one with an invite code to start talking.",
                actionLabel = "BROWSE CHANNELS",
                onAction = onGoChannels,
            )
            else -> LazyColumn(Modifier.weight(1f)) {
                items(channels, key = { it.id.value }) { channel ->
                    Card(
                        onClick = { onOpenChannel(channel.id.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(channel.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${channel.memberCount} member(s) · epoch ${channel.epoch}" +
                                    if (channel.isLocal) " · LOCAL" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = StGrey(),
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onGoChannels, modifier = Modifier.weight(1f)) { Text("CHANNELS") }
            OutlinedButton(onClick = onGoLocalNetwork, modifier = Modifier.weight(1f)) { Text("OFFLINE") }
            OutlinedButton(onClick = onGoSettings, modifier = Modifier.weight(1f)) { Text("SETTINGS") }
        }
    }
}

@Composable
private fun StAmber() = com.securetalk.core.design.StColors.Amber

@Composable
private fun StGrey() = com.securetalk.core.design.StColors.GreyText
