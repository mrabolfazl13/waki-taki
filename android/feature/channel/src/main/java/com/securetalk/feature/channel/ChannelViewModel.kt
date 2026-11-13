package com.securetalk.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.common.domain.AudioEngine
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.domain.GroupKeyManager
import com.securetalk.core.common.domain.ParticipantPresence
import com.securetalk.core.common.domain.PushToTalkController
import com.securetalk.core.common.model.AudioRoute
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.common.model.FloorState
import com.securetalk.core.common.model.Member
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val ptt: PushToTalkController,
    private val channelManager: ChannelManager,
    private val presence: ParticipantPresence,
    private val connectionMonitor: ConnectionMonitor,
    private val audioEngine: AudioEngine,
    private val groupKeyManager: GroupKeyManager,
) : ViewModel() {

    val floorState: StateFlow<FloorState> = ptt.floorState
    val transmitting: StateFlow<Boolean> = ptt.transmitting
    val members: StateFlow<List<Member>> = presence.activeChannelMembers
    val connectionState: StateFlow<ConnectionState> = connectionMonitor.state
    val audioRoute: StateFlow<AudioRoute> = audioEngine.route
    val routeOptions: StateFlow<List<AudioRoute>> = audioEngine.routeOptions

    private val _activeChannelId = MutableStateFlow<ChannelId?>(null)
    val activeChannelId: StateFlow<ChannelId?> = _activeChannelId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun openChannel(channelId: String) {
        val id = ChannelId(channelId)
        _activeChannelId.value = id
        ptt.setActiveChannel(id)
        viewModelScope.launch {
            presence.setActiveChannel(id)
            channelManager.members(id)
        }
    }

    fun leave() {
        viewModelScope.launch {
            val id = _activeChannelId.value ?: return@launch
            ptt.release()
            presence.setActiveChannel(null)
            channelManager.leaveChannel(id)
            _activeChannelId.value = null
        }
    }

    fun rotateEpoch() {
        viewModelScope.launch {
            val id = _activeChannelId.value ?: return@launch
            groupKeyManager.rotateEpoch(id)
                .onSuccess { _lastError.value = null }
                .onFailure { _lastError.value = "Epoch rotation failed: ${it.message}" }
        }
    }

    /** PTT press: permission must be granted by the UI before calling. */
    fun onPttDown(micGranted: Boolean) {
        if (!micGranted) {
            _lastError.value = "Microphone permission required"
            return
        }
        viewModelScope.launch {
            ptt.press()
                .onFailure { _lastError.value = it.message }
        }
    }

    fun onPttUp() {
        viewModelScope.launch { ptt.release() }
    }

    fun setRoute(route: AudioRoute) {
        viewModelScope.launch { audioEngine.setRoute(route) }
    }

    fun clearError() {
        _lastError.value = null
    }

    override fun onCleared() {
        // release audio + floor when the screen VM dies (no leaks)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            ptt.release()
            audioEngine.stopPlayback()
        }
        super.onCleared()
    }
}
