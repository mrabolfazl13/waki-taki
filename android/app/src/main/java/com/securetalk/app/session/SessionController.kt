package com.securetalk.app.session

import com.securetalk.app.di.ModeHolder
import com.securetalk.core.common.domain.AudioEngine
import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.domain.ConnectionMonitor
import com.securetalk.core.common.domain.ConnectionTransition
import com.securetalk.core.common.domain.GroupKeyManager
import com.securetalk.core.common.domain.ParticipantPresence
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.protocol.WsEvent
import com.securetalk.core.security.group.EnvelopeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session glue started once at app start:
 *  1. ConnectionMonitor lifecycle + mode switch bookkeeping (ModeHolder, §8).
 *  2. Incoming pairwise sender-key envelopes -> GroupKeyManager.
 *  3. Incoming media.recv binary frames -> decrypt (epoch/sender/seq validation,
 *     replay window) -> AudioEngine playback.
 *  4. On channel activation: start playback; on `channel.event` epoch rotation:
 *     re-fetch envelopes.
 */
@Singleton
class SessionController @Inject constructor(
    private val monitor: ConnectionMonitor,
    private val transport: com.securetalk.core.common.domain.CommunicationTransport,
    private val audioEngine: AudioEngine,
    private val groupKeyManager: GroupKeyManager,
    private val envelopeTransport: EnvelopeTransport,
    private val channelManager: ChannelManager,
    private val presence: ParticipantPresence,
    private val modeHolder: ModeHolder,
    private val scope: CoroutineScope,
) {

    private val activeChannel = AtomicReference<ChannelId?>(null)

    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch { monitor.start() }

        scope.launch {
            monitor.transitions.collect { transition ->
                when (transition) {
                    is ConnectionTransition.Changed -> {
                        modeHolder.useLocal = transition.to == ConnectionState_LOCAL_AVAILABLE
                    }
                    is ConnectionTransition.RequiresUserDecision -> Unit // UI decides (never silent — §8)
                }
            }
        }

        scope.launch {
            // Pairwise-encrypted sender-key envelopes arriving via WS/REST
            envelopeTransport.incoming.collect { envelope ->
                groupKeyManager.handleEnvelopeReceived(envelope)
            }
        }

        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is WsEvent.MediaReceived -> onMedia(event)
                    is WsEvent.ChannelEvent -> onChannelEvent(event)
                    else -> Unit
                }
            }
        }

        scope.launch {
            // Track the active channel from presence so media decrypt routes into playback.
            presence.activeChannelMembers.collect { members ->
                if (members.isEmpty() && activeChannel.get() != null) {
                    setActiveChannelForPlayback(null)
                }
            }
        }
    }

    fun setActiveChannelForPlayback(channelId: String?) {
        val previous = activeChannel.getAndSet(channelId?.let(::ChannelId))
        if (previous == null && channelId != null) {
            scope.launch { audioEngine.startPlayback(ChannelId(channelId)) }
        }
        if (previous != null && channelId == null) {
            scope.launch { audioEngine.stopPlayback() }
        }
        if (previous != null && channelId != null && previous.value != channelId) {
            scope.launch {
                audioEngine.stopPlayback()
                audioEngine.startPlayback(ChannelId(channelId))
            }
        }
    }

    private fun onMedia(event: WsEvent.MediaReceived) {
        val channelId = activeChannel.get() ?: return
        scope.launch {
            groupKeyManager.decryptMedia(channelId, event.packet)
                .onSuccess { decoded -> audioEngine.submitEncoded(channelId, decoded) }
                .onFailure {
                    // stale epoch / replay / revoked sender: drop silently (§3.3)
                }
        }
    }

    private fun onChannelEvent(event: WsEvent.ChannelEvent) {
        if (event.kind == WsEvent.ChannelEventKind.EPOCH_ROTATED && event.channelId == activeChannel.get()) {
            scope.launch {
                // new epoch: fetch envelopes addressed to us (online REST path)
                (envelopeTransport as? com.securetalk.core.network.impl.EnvelopeTransportImpl)
                    ?.fetchPending(event.channelId)
                    ?.onSuccess { envelopes -> envelopes.forEach { groupKeyManager.handleEnvelopeReceived(it) } }
            }
        }
    }

    private companion object {
        // avoid importing ConnectionState name collision confusion
        val ConnectionState_LOCAL_AVAILABLE = com.securetalk.core.common.model.ConnectionState.LOCAL_AVAILABLE
    }
}
