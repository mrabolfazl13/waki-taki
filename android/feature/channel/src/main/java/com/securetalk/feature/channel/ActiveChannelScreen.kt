package com.securetalk.feature.channel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.common.model.FloorState
import com.securetalk.core.common.model.PresenceState
import com.securetalk.core.design.ConnectionBadge
import com.securetalk.core.design.PttButton
import com.securetalk.core.design.StAvatar
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StChip
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.OfflineBanner
import com.securetalk.core.design.stTokens
import com.securetalk.core.permissions.RequestPermissions

/**
 * Active Channel — the core experience. Header with live floor status, member
 * strip with presence avatars, audio route chips, and the custom PTT control
 * wired to PushToTalkController. Logic unchanged from the previous screen.
 */
@Composable
fun ActiveChannelScreen(
    channelId: String,
    channelName: String,
    onLeave: () -> Unit = {},
    onOpenSettings: (String) -> Unit = {},
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
    val t = stTokens()

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
    val connecting = floorState is FloorState.Requesting
    val offline = connection == ConnectionState.OFFLINE

    Column(Modifier.fillMaxSize()) {
        // ---- header -------------------------------------------------------------
        StTopBar(
            title = channelName,
            onBack = {
                viewModel.leave()
                onLeave()
            },
            trailing = {
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
            },
        )

        OfflineBanner(offline)

        // ---- floor status hero ----------------------------------------------------
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val holderLabel = when (val fs = floorState) {
                is FloorState.Speaking ->
                    if (fs.isMine) "YOU ARE SPEAKING" else "SPEAKING · ${holderName(members, fs.holderDeviceId)}"
                is FloorState.Queued -> "IN QUEUE (position ${fs.position})"
                is FloorState.Requesting -> "REQUESTING FLOOR..."
                is FloorState.Denied -> "DENIED · ${fs.reason}"
                FloorState.Idle -> "FLOOR IDLE"
            }
            Text(
                holderLabel,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isSpeaking -> t.txRed
                    isQueueing -> t.amber
                    connecting -> t.teal
                    else -> t.greyText
                },
            )
            Spacer(Modifier.height(10.dp))
            val currentHolder = (floorState as? FloorState.Speaking)?.holderDeviceId
            StAvatar(
                name = if (isSpeaking) "YOU" else currentHolder?.let { holderName(members, it) } ?: "—",
                speaking = isSpeaking,
                size = 56.dp,
            )
        }

        // ---- member strip with live presence ---------------------------------------
        LazyRow(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(members, key = { it.deviceId.value }) { member ->
                val speakingNow = floorState is FloorState.Speaking &&
                    (floorState as FloorState.Speaking).holderDeviceId == member.deviceId
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StAvatar(member.displayName, speaking = speakingNow, size = 42.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        member.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (speakingNow) t.txRed else t.greyText,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---- audio route selector -----------------------------------------------------
        Row(
            Modifier.padding(horizontal = 16.dp),
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

        error?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, color = t.txRed, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::clearError) { Text("DISMISS") }
            }
        }

        Spacer(Modifier.weight(1f))

        // ---- PTT: requires mic permission; mic-type foreground service starts only
        // AFTER the permission is granted (Android 14 foreground-service rules).
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

        Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            PttButton(
                isSpeaking = isSpeaking,
                isQueueing = isQueueing,
                queuePosition = queuePosition,
                connecting = connecting,
                error = false,
                enabled = micGranted && !offline,
                onPttDown = { viewModel.onPttDown(micGranted) },
                onPttUp = viewModel::onPttUp,
            )
        }

        // ---- footer actions ------------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StButton("ROTATE KEYS", viewModel::rotateEpoch, Modifier.weight(1f), style = StButtonStyle.Ghost, compact = true)
            StButton("INFO", { onOpenSettings(channelId) }, Modifier.weight(0.6f), style = StButtonStyle.Ghost, compact = true)
            StButton("LEAVE", {
                viewModel.leave()
                onLeave()
            }, Modifier.weight(0.8f), style = StButtonStyle.Danger, compact = true)
        }
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
    val t = stTokens()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StTopBar(title = "Channel info", onBack = null)
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Text("CHANNEL", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Text(channelId, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("${members.size} member(s) — max 20 per channel", style = MaterialTheme.typography.bodyMedium)
        }
        StButton("INVITE MEMBERS", { onInvite(channelId) }, Modifier.padding(horizontal = 16.dp))
        StCard(Modifier.padding(horizontal = 16.dp)) {
            Text("KEY ROTATION", style = MaterialTheme.typography.labelLarge, color = t.greyText)
            Text(
                "Key rotation creates a new sender key epoch: all senders re-distribute their " +
                    "keys pairwise-encrypted. Membership changes should trigger rotation.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
