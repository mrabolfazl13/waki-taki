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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ParticipantPresence
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.Member
import com.securetalk.core.common.model.MemberRole
import com.securetalk.core.design.StAvatar
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StChip
import com.securetalk.core.design.StTopBar
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.stTokens
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

/** Member roster: avatar, presence, role, mic state; owner can remove members. */
@Composable
fun MembersScreen(
    channelId: String,
    onInvite: (String) -> Unit,
    viewModel: MembersViewModel = hiltViewModel(),
) {
    val members by viewModel.members.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val t = stTokens()

    LaunchedEffect(channelId) { viewModel.load(channelId) }

    Column(Modifier.fillMaxSize()) {
        StTopBar(
            title = "Members",
            trailing = { StChip("${members.size}/20") },
        )

        error?.let {
            Text(it, color = t.txRed, modifier = Modifier.padding(horizontal = 16.dp))
        }

        when {
            loading -> Column {
                repeat(4) {
                    StCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("Loading members…", style = MaterialTheme.typography.bodyMedium, color = t.greyText)
                    }
                }
            }
            members.isEmpty() -> StateBlock(
                title = "No members cached",
                detail = "Members are loaded from the server or host. Invite people to add them.",
                actionLabel = "INVITE",
                onAction = { onInvite(channelId) },
            )
            else -> LazyColumn(Modifier.weight(1f)) {
                items(members, key = { it.deviceId.value }) { member ->
                    StCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val speaking = member.state.name == "SPEAKING"
                            StAvatar(member.displayName, speaking = speaking, size = 42.dp)
                            Column(Modifier.weight(1f)) {
                                Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StChip(
                                        member.role.name.lowercase(),
                                        accent = if (member.role == MemberRole.OWNER) t.lime else null,
                                    )
                                    StChip(
                                        member.state.name.lowercase(),
                                        accent = when (member.state.name) {
                                            "SPEAKING" -> t.txRed
                                            "ONLINE" -> t.emerald
                                            else -> null
                                        },
                                    )
                                }
                            }
                            if (member.role != MemberRole.OWNER) {
                                StButton(
                                    "REMOVE",
                                    { viewModel.remove(channelId, member.deviceId.value) },
                                    style = StButtonStyle.Ghost,
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        StButton(
            "INVITE MEMBERS",
            { onInvite(channelId) },
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
