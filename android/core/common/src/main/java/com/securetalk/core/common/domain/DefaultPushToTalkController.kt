package com.securetalk.core.common.domain

import com.securetalk.core.common.model.AudioFrame
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.FloorState
import com.securetalk.core.common.protocol.WsCommand
import com.securetalk.core.common.protocol.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reference PushToTalkController (ARCHITECTURE §4):
 *
 *  press   -> floor.request -> await floor.granted (3 s timeout) -> start TX
 *  release -> floor.release -> stop TX
 *
 * Handles denial, queue position display, request timeout, lease expiry
 * (auto release at lease_expires_ms). The client NEVER grants itself the
 * floor locally — speaking state only ever comes from server events.
 *
 * Pure Kotlin over the §2.3 abstractions so it is unit-testable on the JVM.
 */
class DefaultPushToTalkController(
    private val transport: CommunicationTransport,
    private val audioEngine: AudioEngine,
    private val groupKeyManager: GroupKeyManager,
    private val ownDeviceId: DeviceId,
    private val requestTimeoutMs: Long = 3_000L,
    private val leaseRenewMarginMs: Long = 5_000L,
    private val eventBufferCapacity: Int = 64,
    scope: CoroutineScope? = null,
) : PushToTalkController {

    private val controllerScope = scope ?: CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _floorState = MutableStateFlow<FloorState>(FloorState.Idle)
    override val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _transmitting = MutableStateFlow(false)
    override val transmitting: StateFlow<Boolean> = _transmitting.asStateFlow()

    private var activeChannelId: ChannelId? = null
    private var captureJob: Job? = null
    private var leaseJob: Job? = null
    private var pressGeneration = 0L

    override fun setActiveChannel(channelId: ChannelId?) {
        if (channelId != activeChannelId && _transmitting.value) {
            // Channel switched mid-transmission: release the floor of the old channel.
            controllerScope.launch { releaseInternal() }
        }
        activeChannelId = channelId
    }

    override suspend fun press(): Result<Unit> {
        val channelId = activeChannelId
            ?: return Result.failure(IllegalStateException("No active channel"))
        when (val current = _floorState.value) {
            is FloorState.Speaking -> if (current.isMine) return Result.success(Unit)
            is FloorState.Requesting, is FloorState.Queued -> return Result.success(Unit)
            else -> Unit
        }

        pressGeneration++
        val generation = pressGeneration
        _floorState.value = FloorState.Requesting(channelId, System.currentTimeMillis())

        val sendOk = transport.send(WsCommand.FloorRequest(channelId)).isSuccess
        if (!sendOk) {
            _floorState.value = FloorState.Denied(channelId, "Transport unavailable")
            return Result.failure(IllegalStateException("Transport unavailable"))
        }

        // §4: request timeout 3 s -> treat as denial.
        val outcome = withTimeoutOrNull(requestTimeoutMs) {
            transport.events.first { event ->
                when (event) {
                    // Never start transmitting for someone else's grant.
            is WsEvent.FloorGranted -> event.channelId == channelId && event.holder == ownDeviceId
                    is WsEvent.FloorDenied -> event.channelId == channelId
                    else -> false
                }
            }
        }

        if (controllerScope.coroutineContext.isActive.not()) return Result.failure(IllegalStateException("Cancelled"))

        return when (outcome) {
            is WsEvent.FloorGranted -> {
                if (pressGeneration != generation) {
                    // stale grant (user released again meanwhile)
                    transport.send(WsCommand.FloorRelease(channelId))
                    Result.failure(IllegalStateException("Stale grant"))
                } else {
                    startTransmitting(channelId, outcome.leaseExpiresMs)
                    Result.success(Unit)
                }
            }
            is WsEvent.FloorDenied -> {
                _floorState.value = if (outcome.queuePosition != null) {
                    FloorState.Queued(channelId, outcome.queuePosition)
                } else {
                    FloorState.Denied(channelId, outcome.reason)
                }
                Result.failure(FloorDeniedException(outcome.reason, outcome.queuePosition))
            }
            null -> {
                _floorState.value = FloorState.Denied(channelId, "Request timeout")
                Result.failure(FloorTimeoutException())
            }
            else -> Result.failure(IllegalStateException("Unexpected event"))
        }
    }

    private suspend fun startTransmitting(channelId: ChannelId, leaseExpiresMs: Long) {
        val membersResult = groupKeyManager.ensureSenderKey(channelId, emptyList())
        membersResult.onFailure {
            _floorState.value = FloorState.Denied(channelId, "Sender key unavailable: ${it.message}")
            transport.send(WsCommand.FloorRelease(channelId))
            return
        }

        captureResult().onFailure {
            _floorState.value = FloorState.Denied(channelId, "Microphone unavailable: ${it.message}")
            transport.send(WsCommand.FloorRelease(channelId))
            return
        }

        _floorState.value = FloorState.Speaking(
            channelId,
            ownDeviceId,
            leaseExpiresMs,
            isMine = true,
        )
        _transmitting.value = true

        scheduleLeaseExpiry(channelId, leaseExpiresMs)

        captureJob = controllerScope.launch {
            audioEngine.captureFrames.collect { frame ->
                val encrypted = groupKeyManager.encryptMedia(channelId, frameToBytes(frame))
                encrypted.onSuccess { transport.sendMedia(it) }
            }
        }
    }

    /** AudioEngine implementation detail — frames are 16-bit LE PCM; keep framing here. */
    private fun frameToBytes(frame: AudioFrame): ByteArray {
        val out = ByteArray(frame.samples.size * 2)
        for (i in frame.samples.indices) {
            val v = frame.samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private suspend fun captureResult(): Result<Unit> = audioEngine.startCapture()

    private fun scheduleLeaseExpiry(channelId: ChannelId, leaseExpiresMs: Long) {
        leaseJob?.cancel()
        leaseJob = controllerScope.launch {
            val remaining = leaseExpiresMs - System.currentTimeMillis() - leaseRenewMarginMs
            if (remaining > 0) delay(remaining)
            // Lease nearly expired; release the floor (§4: lease TTL 60 s, auto-release).
            if (_transmitting.value) releaseInternal()
        }
    }

    override suspend fun release() {
        releaseInternal()
    }

    private suspend fun releaseInternal() {
        val channelId = activeChannelId ?: return
        pressGeneration++
        captureJob?.cancel()
        captureJob = null
        leaseJob?.cancel()
        leaseJob = null
        audioEngine.stopCapture()
        _transmitting.value = false
        _floorState.value = FloorState.Idle
        transport.send(WsCommand.FloorRelease(channelId))
    }

    override fun cancel() {
        pressGeneration++
        captureJob?.cancel()
        leaseJob?.cancel()
        controllerScope.cancel()
        _transmitting.value = false
        _floorState.value = FloorState.Idle
    }
}

class FloorDeniedException(val reason: String, val queuePosition: Int?) :
    Exception("Floor denied: $reason${queuePosition?.let { " (queue position $it)" } ?: ""}")

class FloorTimeoutException : Exception("Floor request timed out")
