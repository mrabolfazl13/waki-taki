package com.securetalk.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.securetalk.core.design.PttButton
import com.securetalk.core.design.StAvatar
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StChip
import com.securetalk.core.design.StColors
import com.securetalk.core.design.StSectionLabel
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.stTokens
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

/**
 * Command Center — the main screen. Shows network state, encryption status,
 * active channel hero, quick channel access and a primary PTT action that
 * routes into the channel (floor control lives in the channel session).
 */
@Composable
fun HomeScreen(
    onOpenChannel: (String, String) -> Unit,
    onGoChannels: () -> Unit,
    onGoLocalNetwork: () -> Unit,
    onGoSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val decision by viewModel.pendingDecision.collectAsState()
    val t = stTokens()

    val online = connection == ConnectionState.ONLINE_CONNECTED || connection == ConnectionState.LOCAL_AVAILABLE
    val degraded = connection == ConnectionState.DEGRADED || connection == ConnectionState.RECONNECTING
    val connectionLabel = when (connection) {
        ConnectionState.ONLINE_CONNECTED -> "Online"
        ConnectionState.DEGRADED -> "Degraded"
        ConnectionState.RECONNECTING -> "Reconnecting"
        ConnectionState.LOCAL_AVAILABLE -> "Local mode"
        ConnectionState.OFFLINE -> "Offline"
    }
    val activeChannel = channels.firstOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- status strip ---------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("COMMAND CENTER", style = MaterialTheme.typography.labelSmall, color = t.greyText)
                Text("SecureTalk", style = MaterialTheme.typography.titleLarge)
            }
            ConnectionBadge(state = connectionLabel, connected = online, degraded = degraded)
        }

        // ---- reconnect decision ---------------------------------------------
        AnimatedVisibility(visible = decision != null, enter = fadeIn() + scaleIn(initialScale = 0.95f)) {
            decision?.let { d ->
                StCard(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    accent = t.amber,
                ) {
                    Text("CONNECTION LOST", style = MaterialTheme.typography.labelLarge, color = t.amber)
                    Text(d.reason, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StButton("SWITCH TO LOCAL", viewModel::confirmSwitchToLocal, compact = true)
                        StButton("STAY OFFLINE", viewModel::stayOnline, compact = true, style = com.securetalk.core.design.StButtonStyle.Ghost)
                    }
                }
            }
        }

        // ---- crypto / link hero ----------------------------------------------
        StCard(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            accent = if (online) t.emerald else t.txRed,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("END-TO-END ENCRYPTED", style = MaterialTheme.typography.labelLarge, color = t.emerald)
                    Text(
                        "Sender-key group encryption · relays carry opaque packets only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = t.greyText,
                    )
                }
                StChip(if (online) "SECURE LINK" else "NO LINK", accent = if (online) t.emerald else t.txRed)
            }
        }

        // ---- active channel / PTT hero ---------------------------------------
        if (activeChannel != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(activeChannel.name, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                StChip("${activeChannel.memberCount} MEMBERS · EPOCH ${activeChannel.epoch}" + if (activeChannel.isLocal) " · LOCAL" else "")
                Spacer(Modifier.height(14.dp))
                PttButton(
                    isSpeaking = false,
                    isQueueing = false,
                    queuePosition = null,
                    connecting = false,
                    error = false,
                    enabled = true,
                    onPttDown = { onOpenChannel(activeChannel.id.value, activeChannel.name) },
                    onPttUp = { },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open the channel to hold the floor",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.greyText,
                )
            }
        } else {
            StateBlock(
                title = "No channels yet",
                detail = "Create a channel or join one with an invite code to start talking.",
                actionLabel = "BROWSE CHANNELS",
                onAction = onGoChannels,
            )
        }

        // ---- quick channels ----------------------------------------------------
        if (channels.size > 1) {
            StSectionLabel("QUICK ACCESS")
            LazyRow(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(channels.drop(1), key = { it.id.value }) { channel ->
                    StCard(onClick = { onOpenChannel(channel.id.value, channel.name) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            StAvatar(channel.name, size = 44.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(channel.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${channel.memberCount} · e${channel.epoch}",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.greyText,
                            )
                        }
                    }
                }
            }
        }

        // ---- bottom actions ------------------------------------------------------
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StButton("CHANNELS", onGoChannels, Modifier.weight(1f), style = com.securetalk.core.design.StButtonStyle.Ghost, compact = true)
            StButton("OFFLINE", onGoLocalNetwork, Modifier.weight(1f), style = com.securetalk.core.design.StButtonStyle.Ghost, compact = true)
            StButton("SETTINGS", onGoSettings, Modifier.weight(1f), style = com.securetalk.core.design.StButtonStyle.Ghost, compact = true)
        }
        Spacer(Modifier.height(24.dp))
    }
}
