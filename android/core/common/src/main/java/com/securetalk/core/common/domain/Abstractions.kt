package com.securetalk.core.common.domain

import com.securetalk.core.common.model.AudioFrame
import com.securetalk.core.common.model.AudioRoute
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.ChannelInfo
import com.securetalk.core.common.model.ConnectionState
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.InvitePayload
import com.securetalk.core.common.model.KeyEnvelope
import com.securetalk.core.common.model.LocalHostInfo
import com.securetalk.core.common.model.MediaPacket
import com.securetalk.core.common.model.Member
import com.securetalk.core.common.model.PresenceState
import com.securetalk.core.common.model.PreKeyBundleData
import com.securetalk.core.common.model.TransportState
import com.securetalk.core.common.protocol.WsCommand
import com.securetalk.core.common.protocol.WsEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mode-agnostic abstractions (ARCHITECTURE §2.3). Both OnlineTransport and
 * LocalTransport implement these; UI and audio layers depend only on interfaces.
 */

/** WebSocket/REST communication with either the backend or the local host. */
interface CommunicationTransport {
    val events: SharedFlow<WsEvent>
    val state: StateFlow<TransportState>

    /** Establishes the session (auth + WS). Never reuses expired security state — §3.4. */
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun send(command: WsCommand): Result<Unit>

    /** Sends an encrypted media packet as a binary WS frame (media.send). */
    suspend fun sendMedia(packet: MediaPacket)

    /**
     * Routes a pairwise-encrypted key envelope. Online mode uses the REST
     * `keys/distribute` endpoint; local mode uses the WS relay — both must be
     * opaque to the relay (host cannot decrypt; §2.2).
     */
    suspend fun sendKeyEnvelope(envelope: KeyEnvelope): Result<Unit>

    suspend fun setActiveChannel(channelId: ChannelId?)
}

/** Capture/playback of audio, independent of codec and transport. */
interface AudioEngine {
    /** Hot flow of captured PCM frames; active between startCapture/stopCapture. */
    val captureFrames: Flow<AudioFrame>
    val route: StateFlow<AudioRoute>
    val routeOptions: StateFlow<List<AudioRoute>>

    suspend fun startCapture(): Result<Unit>
    suspend fun stopCapture()
    suspend fun startPlayback(channelId: ChannelId)
    suspend fun stopPlayback()

    /**
     * Submits an encoded (post-decrypt) frame into the playback jitter buffer.
     * The engine handles jitter buffering, decoding and packet-loss concealment.
     */
    fun submitEncoded(channelId: ChannelId, encodedPayload: ByteArray, plc: Boolean = false)

    suspend fun setRoute(route: AudioRoute)

    /** Releases all AudioRecord/AudioTrack resources (call on onDispose/onStop). */
    fun release()
}

/**
 * Pairwise double-ratchet-lite sessions used for sender-key distribution
 * (ARCHITECTURE §3.2). Sessions are per peer device.
 */
interface SecureSessionManager {
    /** Establishes (or re-establishes) a session using the remote prekey bundle. */
    suspend fun ensureSession(peer: DeviceId, remoteBundle: PreKeyBundleData?): Result<Unit>

    suspend fun encryptForPeer(peer: DeviceId, plaintext: ByteArray, aad: ByteArray): Result<ByteArray>
    suspend fun decryptFromPeer(peer: DeviceId, ciphertext: ByteArray, aad: ByteArray): Result<ByteArray>

    /** Drops session state — transport switches and expiry always re-establish (§3.4, §8). */
    suspend fun resetSession(peer: DeviceId)
    suspend fun resetAll()
}

/**
 * Sender-key group media scheme (ARCHITECTURE §3.3): sender key generation,
 * pairwise distribution, per-packet AES-256-GCM, epoch rotation on membership change.
 */
interface GroupKeyManager {
    /** Ensures this device has a sender key for [channelId] at the current epoch, distributed to [members]. */
    suspend fun ensureSenderKey(channelId: ChannelId, members: List<DeviceId>): Result<Unit>

    /** Rotates the epoch (membership change). Returns the new epoch. */
    suspend fun rotateEpoch(channelId: ChannelId): Result<Long>

    suspend fun encryptMedia(channelId: ChannelId, plaintextFrame: ByteArray): Result<MediaPacket>

    /** Decrypts and validates epoch + replay window; rejects stale epochs and revoked senders. */
    suspend fun decryptMedia(channelId: ChannelId, packet: MediaPacket): Result<ByteArray>

    /** Applies a pairwise-encrypted sender-key envelope addressed to us. */
    suspend fun handleEnvelopeReceived(envelope: KeyEnvelope): Result<Unit>
}

interface ChannelManager {
    val channels: Flow<List<ChannelInfo>>
    suspend fun refresh(): Result<Unit>
    suspend fun createChannel(name: String): Result<ChannelInfo>
    suspend fun joinChannel(inviteCode: String): Result<ChannelInfo>
    suspend fun leaveChannel(id: ChannelId): Result<Unit>
    suspend fun deleteChannel(id: ChannelId): Result<Unit>
    suspend fun createInvite(id: ChannelId): Result<InvitePayload>
    suspend fun validateInvite(code: String): Result<Boolean>
    suspend fun members(id: ChannelId): Result<List<Member>>
    suspend fun removeMember(id: ChannelId, userId: DeviceId): Result<Unit>
}

interface ParticipantPresence {
    fun observe(channelId: ChannelId): Flow<List<Member>>
    val activeChannelMembers: StateFlow<List<Member>>
    suspend fun setActiveChannel(channelId: ChannelId?)
}

sealed interface ConnectionTransition {
    data class Changed(val from: ConnectionState, val to: ConnectionState) : ConnectionTransition

    /**
     * §8: never a silent downgrade. When internet is lost but local hosts are
     * discovered, the UI must ask the user before switching (re-establishes
     * security sessions afterwards).
     */
    data class RequiresUserDecision(
        val reason: String,
        val localHosts: List<LocalHostInfo>,
    ) : ConnectionTransition
}

interface ConnectionMonitor {
    val state: StateFlow<ConnectionState>
    val transitions: Flow<ConnectionTransition>
    val localHosts: StateFlow<List<LocalHostInfo>>

    /** §8: current unresolved decision the user must act on (internet lost, local hosts found). */
    val pendingDecision: StateFlow<ConnectionTransition.RequiresUserDecision?>
    fun dismissPendingDecision()

    suspend fun start()
    suspend fun stop()

    /** Explicit user-confirmed switch (online ↔ local). Re-runs session establishment. */
    suspend fun confirmSwitchToLocal(host: LocalHostInfo): Result<Unit>
    suspend fun confirmSwitchToOnline(): Result<Unit>

    /** Manual re-probe of known local hosts (NSD). */
    suspend fun probeLocalHosts()
}

interface PushToTalkController {
    val floorState: StateFlow<com.securetalk.core.common.model.FloorState>
    val transmitting: StateFlow<Boolean>

    /** press -> floor.request -> await granted -> start TX. */
    suspend fun press(): Result<Unit>

    /** release -> floor.release -> stop TX. */
    suspend fun release()

    /** Scopes PTT to a channel; releasing when switching channels mid-TX. */
    fun setActiveChannel(channelId: com.securetalk.core.common.model.ChannelId?)

    /** Cancels everything (called from onDispose / service shutdown). */
    fun cancel()
}
