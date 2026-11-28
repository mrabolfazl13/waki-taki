package com.securetalk.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StChip
import com.securetalk.core.design.StColors
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.stTokens
import com.securetalk.core.network.api.DeviceResponse
import com.securetalk.core.network.api.SecureTalkApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val api: SecureTalkApi,
) : ViewModel() {

    private val _devices = MutableStateFlow<List<DeviceResponse>>(emptyList())
    val devices: StateFlow<List<DeviceResponse>> = _devices.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { api.myDevices() }
                .onSuccess { _devices.value = it }
                .onFailure { _error.value = it.message ?: "Could not load devices (offline?)" }
            _loading.value = false
        }
    }

    fun revoke(deviceId: String) {
        viewModelScope.launch {
            runCatching { api.deleteDevice(deviceId) }
                .onSuccess { load() }
                .onFailure { _error.value = it.message }
        }
    }
}

/** Devices screen: registered devices with identity keys, revoke support (§5). */
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    val t = stTokens()
    Column(Modifier.fillMaxSize()) {
        StTopBar(title = "Devices", trailing = { StChip("${devices.size} REGISTERED") })
        error?.let {
            Text(it, color = t.txRed, modifier = Modifier.padding(horizontal = 16.dp))
        }
        when {
            loading -> StateBlock(title = "Loading devices...", loading = true)
            devices.isEmpty() -> StateBlock(
                title = "No devices registered",
                detail = "Devices register identity keys and prekeys when you sign in.",
                actionLabel = "RETRY",
                onAction = viewModel::load,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(devices, key = { it.device_id }) { device ->
                    StCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            device.device_id,
                            style = MaterialTheme.typography.labelSmall,
                            color = t.greyText,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "identity: ${device.identity_pubkey.take(16)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.teal,
                        )
                        Spacer(Modifier.height(8.dp))
                        StButton(
                            "REVOKE",
                            { viewModel.revoke(device.device_id) },
                            style = StButtonStyle.Ghost,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}
