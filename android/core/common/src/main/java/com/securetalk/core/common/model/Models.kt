package com.securetalk.core.common.model

/**
 * Core domain models shared by every layer of the app.
 * Pure Kotlin — no Android imports — so this module is unit-testable on the JVM.
 */

@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class UserId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class ChannelId(val value: String) {
    override fun toString(): String = value
}

enum class MemberRole { OWNER, ADMIN, MEMBER }

enum class PresenceState { ONLINE, SPEAKING, IDLE, OFFLINE }

data class Member(
    val userId: UserId,
    val deviceId: DeviceId,
    val displayName: String,
    val role: MemberRole,
    val state: PresenceState = PresenceState.OFFLINE,
)

data class ChannelInfo(
    val id: ChannelId,
    val name: String,
    val memberCount: Int,
    val isLocal: Boolean,
    val epoch: Long = 0L,
    val isOwner: Boolean = false,
)

data class InvitePayload(
    val inviteCode: String,
    val qrPayload: String,
)

data class LocalHostInfo(
    val hostName: String,
    val address: String,
    val port: Int,
)

/** Floor-control state as observed by this client (never self-granted — see ARCHITECTURE §4). */
sealed interface FloorState {
    data object Idle : FloorState
    data class Requesting(val channelId: ChannelId, val requestedAtMs: Long) : FloorState
    data class Queued(val channelId: ChannelId, val position: Int) : FloorState
    data class Speaking(
        val channelId: ChannelId,
        val holderDeviceId: DeviceId,
        val leaseExpiresAtMs: Long,
        val isMine: Boolean,
    ) : FloorState
    data class Denied(val channelId: ChannelId, val reason: String) : FloorState
}

/** ConnectionMonitor states per ARCHITECTURE §8. */
enum class ConnectionState {
    ONLINE_CONNECTED,
    DEGRADED,
    RECONNECTING,
    LOCAL_AVAILABLE,
    OFFLINE,
}

/** One 20 ms PCM audio frame. [plc] marks packet-loss-concealment (synthetic) frames. */
class AudioFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val plc: Boolean = false,
) {
    val durationMs: Int get() = samples.size * 1000 / sampleRateHz.coerceAtLeast(1)
}

/**
 * Encrypted media packet as it travels between peers.
 * Wire framing (ARCHITECTURE §6): epoch(4) | sender_device_id(16) | seq(4) | ciphertext+tag.
 * Only [payload] is encrypted; the header is plaintext for relay routing.
 */
class MediaPacket(
    val epoch: Int,
    val sender: DeviceId,
    val sequence: Long,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MediaPacket && other.epoch == epoch && other.sender == sender &&
            other.sequence == sequence && other.payload.contentEquals(payload)

    override fun hashCode(): Int =
        31 * (31 * (31 * epoch + sender.value.hashCode()) + sequence.hashCode()) + payload.contentHashCode()
}

/**
 * Sender-key envelope distributed pairwise-encrypted (ARCHITECTURE §3.3).
 * Relays and the local host never see the plaintext — they route by [recipientDeviceId] only.
 */
class KeyEnvelope(
    val channelId: ChannelId,
    val epoch: Long,
    val senderDeviceId: DeviceId,
    val recipientDeviceId: DeviceId,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KeyEnvelope && other.channelId == channelId && other.epoch == epoch &&
            other.senderDeviceId == senderDeviceId && other.recipientDeviceId == recipientDeviceId &&
            other.ciphertext.contentEquals(ciphertext)

    override fun hashCode(): Int =
        channelId.value.hashCode() * 31 + (epoch.hashCode() * 31 + ciphertext.contentHashCode())
}

/** Public prekey bundle published for X3DH-style session establishment (ARCHITECTURE §3.1). */
class PreKeyBundleData(
    val deviceId: DeviceId,
    val identitySigningPub: ByteArray,   // Ed25519 verify key (Tink-serialized public keyset)
    val signedPrekeyPub: ByteArray,      // X25519 prekey public point
    val prekeySignature: ByteArray,      // Ed25519 signature over signedPrekeyPub
    val oneTimePrekeyPub: ByteArray? = null,
    val oneTimePrekeyId: Long? = null,
)

enum class TransportState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/** Audio output routing. */
enum class AudioRoute { SPEAKER, EARPIECE, WIRED, BLUETOOTH }

data class InviteCode(val code: String)
