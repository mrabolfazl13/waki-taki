package com.securetalk.feature.channel

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.gestures.detectTapGestures
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.common.model.FloorState
import com.securetalk.core.common.model.PresenceState
import com.securetalk.core.design.ConnectionBadge
import com.securetalk.core.design.PttBar
import com.securetalk.core.design.StColors
import com.securetalk.core.permissions.RequestPermissions

/**
 * Active Channel screen (spec §8) — every element wired to real services:
 *  - PTT bar -> PushToTalkController (floor.request/granted/denied/queue/lease)
 *  - Speaker indicator from floor events / presence
 *  - Connection quality from ConnectionMonitor (§8 states)
 *  - Audio route selector (speaker/earpiece/wired/Bluetooth)
 *  - Member strip with live presence
 */
@Composable
fun ActiveChannelScreen(
    channelId: String,
    channelName: String,
    onLeave: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onStartPttService: () -> Unit = {},
    onStopPttService: () -> Unit = {},
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val floorState by viewModel.floorState.collectAsState()
    val members by viewModel.members.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val route by viewModel.audioRoute.collectAsState()
    val routeOptions by viewModel.routeOptions.collectAsState()
    val error by viewModel.lastError.collectAsState()

    var micGranted by remember { mutableStateOf(false) }

    DisposableEffect(channelId) {
        viewModel.openChannel(channelId)
        onDispose {
            viewModel.onPttUp()
        }
    }

    val isSpeaking = floorState is FloorState.Speaking && (floorState as FloorState.Speaking).isMine
    val isQueueing = floorState is FloorState.Queued
    val queuePosition = (floorState as? FloorState.Queued)?.position
    val offline = connection == ConnectionState.OFFLINE

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(channelName, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (val fs = floorState) {
                        is FloorState.Speaking ->
                            if (fs.isMine) "YOU ARE SPEAKING" else "SPEAKING: ${holderName(members, fs.holderDeviceId)}"
                        is FloorState.Queued -> "IN QUEUE (position ${fs.position})"
                        is FloorState.Requesting -> "REQUESTING FLOOR..."
                        is FloorState.Denied -> "DENIED: ${fs.reason}"
                        FloorState.Idle -> "FLOOR IDLE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StColors.GreyText,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionBadge(
                    state = when (connection) {
                        ConnectionState.ONLINE_CONNECTED -> "Good"
                        ConnectionState.DEGRADED -> "Weak"
                        ConnectionState.RECONNECTING -> "Reconnecting"
                        ConnectionState.LOCAL_AVAILABLE -> "Local"
                        ConnectionState.OFFLINE -> "Offline"
                    },
                    connected = connection == ConnectionState.ONLINE_CONNECTED || connection == ConnectionState.LOCAL_AVAILABLE,
                    degraded = connection == ConnectionState.DEGRADED || connection == ConnectionState.RECONNECTING,
                )
                OutlinedButton(onClick = { onOpenSettings(channelId) }) { Text("INFO") }
            }
        }

        com.securetalk.core.design.OfflineBanner(offline)

        error?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, color = StColors.TxRed, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = viewModel::clearError) { Text("DISMISS") }
            }
        }

        // Member strip with live presence
        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(members, key = { it.deviceId.value }) { member ->
                MemberChip(member)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Audio route selector
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            routeOptions.forEach { option ->
                FilterChip(
                    selected = option == route,
                    onClick = { viewModel.setRoute(option) },
                    label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // PTT: requires mic permission; mic-type foreground service starts only AFTER
        // the permission is granted (Android 14 foreground-service rules).
        if (!micGranted) {
            RequestPermissions(
                permissions = com.securetalk.core.permissions.StPermissions.requiredList,
                onAllGranted = { micGranted = true },
            )
        }
        androidx.compose.runtime.LaunchedEffect(micGranted) {
            if (micGranted) onStartPttService()
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { onStopPttService() }
        }
        Box(Modifier.padding(24.dp)) {
            PttBar(
                isSpeaking = isSpeaking,
                isQueueing = isQueueing,
                queuePosition = queuePosition,
                enabled = micGranted && !offline,
                onPttDown = { viewModel.onPttDown(micGranted) },
                onPttUp = viewModel::onPttUp,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(micGranted, offline) {
                        if (!micGranted || offline) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                viewModel.onPttDown(true)
                                try {
                                    awaitRelease()
                                } finally {
                                    viewModel.onPttUp()
                                }
                            },
                        )
                    },
            )
        }

        // Footer actions
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::rotateEpoch,
                modifier = Modifier.weight(1f),
            ) { Text("ROTATE KEYS") }
            OutlinedButton(
                onClick = {
                    viewModel.leave()
                    onLeave()
                },
                modifier = Modifier.weight(1f),
            ) { Text("LEAVE") }
        }
    }
}

@Composable
private fun MemberChip(member: com.securetalk.core.common.model.Member) {
    val color = when (member.state) {
        PresenceState.SPEAKING -> StColors.TxRed
        PresenceState.ONLINE -> StColors.Green
        PresenceState.IDLE -> StColors.Amber
        PresenceState.OFFLINE -> StColors.Border
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(StColors.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(member.displayName, style = MaterialTheme.typography.labelSmall)
    }
}

private fun holderName(members: List<com.securetalk.core.common.model.Member>, holder: com.securetalk.core.common.model.DeviceId): String =
    members.firstOrNull { it.deviceId == holder }?.displayName ?: holder.value.take(8)

@Composable
fun ChannelSettingsScreen(
    channelId: String,
    onInvite: (String) -> Unit,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val members by viewModel.members.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Channel settings", style = MaterialTheme.typography.headlineMedium)
        Text("Channel: $channelId", style = MaterialTheme.typography.bodyMedium, color = StColors.GreyText)
        Text("${members.size} member(s) — max 20 per channel", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onInvite(channelId) }, modifier = Modifier.fillMaxWidth()) {
            Text("INVITE MEMBERS")
        }
        Text(
            "Key rotation creates a new sender key epoch: all senders re-distribute their " +
                "keys pairwise-encrypted. Membership changes should trigger rotation.",
            style = MaterialTheme.typography.bodyMedium,
            color = StColors.GreyText,
        )
    }
}
