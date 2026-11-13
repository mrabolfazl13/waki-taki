package com.securetalk.feature.members

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
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ParticipantPresence
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.Member
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.StColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MembersViewModel @Inject constructor(
    private val channelManager: ChannelManager,
    private val presence: ParticipantPresence,
) : ViewModel() {

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(channelId: String) {
        viewModelScope.launch {
            _loading.value = true
            val result = channelManager.members(ChannelId(channelId))
            result.onSuccess { _members.value = it }
                .onFailure { _error.value = it.message ?: "Could not load members" }
            _loading.value = false
        }
    }

    fun remove(channelId: String, deviceId: String) {
        viewModelScope.launch {
            channelManager.removeMember(ChannelId(channelId), com.securetalk.core.common.model.DeviceId(deviceId))
                .onSuccess { load(channelId) }
                .onFailure { _error.value = it.message ?: "Remove failed (owner only)" }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

@Composable
fun MembersScreen(
    channelId: String,
    onInvite: (String) -> Unit,
    viewModel: MembersViewModel = hiltViewModel(),
) {
    val members by viewModel.members.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(channelId) { viewModel.load(channelId) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Members", style = MaterialTheme.typography.titleLarge)
            Text(
                "${members.size}/20",
                style = MaterialTheme.typography.labelLarge,
                color = StColors.GreyText,
            )
        }

        error?.let {
            Text(it, color = StColors.TxRed, modifier = Modifier.padding(horizontal = 16.dp))
        }

        when {
            loading -> StateBlock(title = "Loading members...", loading = true)
            members.isEmpty() -> StateBlock(
                title = "No members cached",
                detail = "Members are loaded from the server or host. Invite people to add them.",
                actionLabel = "INVITE",
                onAction = { onInvite(channelId) },
            )
            else -> LazyColumn(Modifier.weight(1f)) {
                items(members, key = { it.deviceId.value }) { member ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${member.role.name.lowercase()} · ${member.state.name.lowercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StColors.GreyText,
                                )
                            }
                            if (member.role != com.securetalk.core.common.model.MemberRole.OWNER) {
                                Button(onClick = { viewModel.remove(channelId, member.deviceId.value) }) {
                                    Text("REMOVE")
                                }
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onInvite(channelId) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) { Text("INVITE MEMBERS") }
        Spacer(Modifier.height(8.dp))
    }
}
