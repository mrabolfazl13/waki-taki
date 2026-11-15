package com.securetalk.feature.channels

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.ChannelInfo
import com.securetalk.core.common.model.InvitePayload
import com.securetalk.core.design.StColors
import com.securetalk.core.network.util.QrBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val channelManager: ChannelManager,
) : ViewModel() {

    val channels: StateFlow<List<ChannelInfo>> = channelManager.channels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _createdInvite = MutableStateFlow<InvitePayload?>(null)
    val createdInvite: StateFlow<InvitePayload?> = _createdInvite.asStateFlow()

    private val _joinResult = MutableStateFlow<ChannelInfo?>(null)
    val joinResult: StateFlow<ChannelInfo?> = _joinResult.asStateFlow()

    fun createChannel(name: String) {
        if (name.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            channelManager.createChannel(name)
                .onSuccess { _joinResult.value = it }
                .onFailure { _error.value = it.message }
            _busy.value = false
        }
    }

    fun join(code: String) {
        if (code.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            channelManager.joinChannel(code.trim())
                .onSuccess { _joinResult.value = it }
                .onFailure { _error.value = it.message ?: "Invite invalid or unreachable" }
            _busy.value = false
        }
    }

    fun createInvite(channelId: String) {
        _busy.value = true
        viewModelScope.launch {
            channelManager.createInvite(ChannelId(channelId))
                .onSuccess { _createdInvite.value = it }
                .onFailure { _error.value = it.message }
            _busy.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}

@Composable
fun CreateChannelScreen(
    onCreated: (String) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val joinResult by viewModel.joinResult.collectAsState()
    var name by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Create channel", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Channel name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        viewModel.error.collectAsState().value?.let {
            Text(it, color = StColors.TxRed)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { viewModel.createChannel(name) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("CREATE") }
    }

    LaunchedEffect(joinResult) {
        joinResult?.let { onCreated(it.id.value) }
    }
}

@Composable
fun JoinChannelScreen(
    onJoined: (String) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val joinResult by viewModel.joinResult.collectAsState()
    var code by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Join with invite", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Invite code or QR payload") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        viewModel.error.collectAsState().value?.let {
            Text(it, color = StColors.TxRed)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { viewModel.join(code) },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("JOIN") }
    }

    LaunchedEffect(joinResult) {
        joinResult?.let { onJoined(it.id.value) }
    }
}

/** Channel invite screen: QR + code (§5 invites endpoint / local QR payload). */
@Composable
fun ChannelInviteScreen(
    channelId: String,
    onDone: () -> Unit = {},
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val invite by viewModel.createdInvite.collectAsState()

    LaunchedEffect(channelId) {
        viewModel.createInvite(channelId)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Invite to channel", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        val payload = invite?.qrPayload
        if (payload != null) {
            val bitmap: Bitmap = remember(payload) { QrBitmap.generate(payload, 512) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Invite QR code",
                modifier = Modifier.size(260.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        invite?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("INVITE CODE", style = MaterialTheme.typography.labelLarge)
                    Text(it.inviteCode, style = MaterialTheme.typography.titleMedium, color = StColors.Amber)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Share the code or QR. Members join with either; max 20 members per channel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StColors.GreyText,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("DONE") }
    }
}
