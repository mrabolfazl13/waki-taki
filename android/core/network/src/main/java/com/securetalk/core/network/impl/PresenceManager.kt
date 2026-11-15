package com.securetalk.core.network.impl

import com.securetalk.core.common.domain.ParticipantPresence
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.Member
import com.securetalk.core.common.model.PresenceState
import com.securetalk.core.common.protocol.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * [ParticipantPresence] fed by `presence.update` and `floor.event` WS events (§6),
 * merged with the member roster from the Room cache.
 */
class PresenceManager(
    private val memberSource: suspend (ChannelId) -> List<Member>,
    private val transport: com.securetalk.core.common.domain.CommunicationTransport,
    scope: CoroutineScope,
) : ParticipantPresence {

    private val scope = scope

    private val membersByChannel = MutableStateFlow<Map<String, List<Member>>>(emptyMap())
    private val activeChannelId = MutableStateFlow<ChannelId?>(null)
    private val _activeChannelMembers = MutableStateFlow<List<Member>>(emptyList())
    override val activeChannelMembers: StateFlow<List<Member>> = _activeChannelMembers.asStateFlow()

    init {
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is WsEvent.PresenceUpdate -> updatePresence(event.channelId.value, event.deviceId, event.state)
                    is WsEvent.FloorEvent -> {
                        val holder = event.holder
                        if (event.state == "speaking" && holder != null) {
                            updateSpeaking(event.channelId.value, holder, true)
                        } else {
                            holder?.let { updateSpeaking(event.channelId.value, it, false) }
                        }
                    }
                    is WsEvent.ChannelEvent -> scope.launch { refreshMembers(event.channelId) }
                    else -> Unit
                }
            }
        }
        scope.launch {
            activeChannelId.collect { channelId ->
                _activeChannelMembers.value = channelId?.let {
                    membersByChannel.value[it.value] ?: memberSource(it)
                } ?: emptyList()
            }
        }
    }

    override fun observe(channelId: ChannelId): Flow<List<Member>> =
        membersByChannel.map { it[channelId.value] ?: emptyList() }

    override suspend fun setActiveChannel(channelId: ChannelId?) {
        activeChannelId.value = channelId
        if (channelId != null) {
            transport.setActiveChannel(channelId)
            refreshMembers(channelId)
        }
    }

    private suspend fun refreshMembers(channelId: ChannelId) {
        val roster = memberSource(channelId)
        val existing = membersByChannel.value[channelId.value].orEmpty()
        // keep live presence states from existing map where roster is fresher but stateless
        val merged = roster.map { member ->
            val live = existing.firstOrNull { it.deviceId == member.deviceId }
            if (live != null && live.state != PresenceState.OFFLINE) member.copy(state = live.state) else member
        }
        membersByChannel.value = membersByChannel.value + (channelId.value to merged)
    }

    private fun updatePresence(channelKey: String, deviceId: DeviceId, state: String) {
        val current = membersByChannel.value[channelKey] ?: return
        val newState = when (state) {
            "speaking" -> PresenceState.SPEAKING
            "online" -> PresenceState.ONLINE
            "idle" -> PresenceState.IDLE
            else -> PresenceState.OFFLINE
        }
        membersByChannel.value = membersByChannel.value + (
            channelKey to current.map {
                if (it.deviceId == deviceId) it.copy(state = newState) else it
            }
            )
    }

    private fun updateSpeaking(channelKey: String, holder: DeviceId, speaking: Boolean) {
        val current = membersByChannel.value[channelKey] ?: return
        membersByChannel.value = membersByChannel.value + (
            channelKey to current.map {
                when (it.deviceId) {
                    holder -> if (speaking) it.copy(state = PresenceState.SPEAKING) else it.copy(state = PresenceState.ONLINE)
                    else -> if (speaking && it.state == PresenceState.SPEAKING) it.copy(state = PresenceState.ONLINE) else it
                }
            }
            )
    }
}
