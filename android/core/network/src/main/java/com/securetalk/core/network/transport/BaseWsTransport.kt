package com.securetalk.core.network.transport

import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.KeyEnvelope
import com.securetalk.core.common.model.MediaPacket
import com.securetalk.core.common.model.MediaPacketCodec
import com.securetalk.core.common.model.TransportState
import com.securetalk.core.common.domain.CommunicationTransport
import com.securetalk.core.common.protocol.WsCommand
import com.securetalk.core.common.protocol.WsEvent
import com.securetalk.core.common.protocol.WsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared WebSocket plumbing for OnlineTransport and LocalTransport.
 * Text frames = JSON envelopes (`{"type": "...", "data": {...}}`); binary frames
 * = media packets (`epoch(4)|sender(16)|seq(4)|ciphertext`) per ARCHITECTURE §6.
 *
 * Automatic reconnection with exponential backoff; security sessions are
 * re-established by ConnectionMonitor on transport switch (never silently).
 */
abstract class BaseWsTransport(
    protected val client: OkHttpClient,
    protected val scope: CoroutineScope,
    protected val deviceId: String = "",
) : CommunicationTransport {

    private val _events = MutableSharedFlow<WsEvent>(replay = 0, extraBufferCapacity = 256)
    override val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    protected var webSocket: WebSocket? = null
    private val userInitiatedClose = AtomicBoolean(false)
    private var reconnectAttempt = 0
    @Volatile protected var activeChannel: ChannelId? = null

    /** URL and per-mode auth for the WS endpoint. */
    protected abstract fun buildRequest(): Request
    protected open fun onOpenHook() = Unit
    protected open fun maxReconnectAttempts(): Int = Int.MAX_VALUE

    override suspend fun connect(): Result<Unit> {
        userInitiatedClose.set(false)
        if (_state.value == TransportState.CONNECTED || _state.value == TransportState.CONNECTING) {
            return Result.success(Unit)
        }
        _state.value = TransportState.CONNECTING
        val request = try {
            buildRequest()
        } catch (e: Exception) {
            _state.value = TransportState.DISCONNECTED
            return Result.failure(e)
        }
        webSocket = client.newWebSocket(request, listener)
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        userInitiatedClose.set(true)
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _state.value = TransportState.DISCONNECTED
    }

    override suspend fun send(command: WsCommand): Result<Unit> {
        val ws = webSocket ?: return Result.failure(IllegalStateException("Not connected"))
        val text = WsProtocol.encode(command)
        return if (ws.send(text)) Result.success(Unit) else Result.failure(IllegalStateException("Send failed"))
    }

    override suspend fun sendMedia(packet: MediaPacket) {
        val ws = webSocket ?: return
        ws.send(MediaPacketCodec.encode(packet).toByteString())
    }

    override suspend fun sendKeyEnvelope(envelope: KeyEnvelope): Result<Unit> {
        // Default: WS relay (local mode). Online transport overrides with REST.
        return send(
            WsCommand.KeyEnvelopeRelay(
                envelope.channelId,
                envelope.recipientDeviceId,
                envelope.epoch,
                java.util.Base64.getEncoder().encodeToString(envelope.ciphertext),
            ),
        )
    }

    override suspend fun setActiveChannel(channelId: ChannelId?) {
        activeChannel = channelId
        sendJoinIfPossible()
    }

    /** Backend/local host require an explicit join before floor or media (§6). */
    protected fun sendJoinIfPossible() {
        val channel = activeChannel ?: return
        val id = deviceId
        if (id.isBlank() || webSocket == null) return
        scope.launch { send(WsCommand.Join(channel, DeviceId(id))) }
    }

    protected fun emit(event: WsEvent) {
        _events.tryEmit(event)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            _state.value = TransportState.CONNECTED
            onOpenHook()
            // Re-join after reconnect: the server drops all state for the old socket.
            sendJoinIfPossible()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            WsProtocol.decode(text)?.let { emit(it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            WsProtocol.decodeMedia(bytes.toByteArray())?.let { emit(it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = TransportState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (userInitiatedClose.get()) return
            _state.value = TransportState.RECONNECTING
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            val attempts = ++reconnectAttempt
            if (attempts > maxReconnectAttempts()) {
                _state.value = TransportState.DISCONNECTED
                return@launch
            }
            // exponential backoff, capped at 30 s
            val backoffMs = minOf(500L * (1L shl minOf(attempts, 6)), 30_000L)
            delay(backoffMs)
            if (!userInitiatedClose.get()) {
                _state.value = TransportState.CONNECTING
                runCatching { webSocket = client.newWebSocket(buildRequest(), listener) }
                    .onFailure { scheduleReconnect() }
            }
        }
    }

    protected fun DeviceId.id(): String = value
}
