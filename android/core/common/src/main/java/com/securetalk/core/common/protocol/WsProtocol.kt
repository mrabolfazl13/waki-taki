package com.securetalk.core.common.protocol

import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.MediaPacket
import com.securetalk.core.common.model.MediaPacketCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WebSocket envelope protocol (ARCHITECTURE §4, §6).
 * Text frames:  `{"type": "...", "data": {...}}`
 * Binary frames: media.recv / media.send headers per [MediaPacketCodec].
 */

sealed interface WsCommand {
    /** Required by the backend before floor/signaling/media (§6). */
    data class Join(val channelId: ChannelId, val deviceId: DeviceId) : WsCommand
    data class FloorRequest(val channelId: ChannelId) : WsCommand
    data class FloorRelease(val channelId: ChannelId) : WsCommand
    data class FloorOverride(val channelId: ChannelId) : WsCommand
    data class SignalOffer(val channelId: ChannelId, val target: DeviceId, val sdp: String) : WsCommand
    data class SignalAnswer(val channelId: ChannelId, val target: DeviceId, val sdp: String) : WsCommand
    data class SignalIce(val channelId: ChannelId, val target: DeviceId, val ice: String) : WsCommand
    data class KeyEnvelopeRelay(
        val channelId: ChannelId,
        val recipient: DeviceId,
        val epoch: Long,
        val envelopeBase64: String,
    ) : WsCommand
    data object Ping : WsCommand
}

sealed interface WsEvent {
    /** Server/local-host acknowledgement of a successful channel join. */
    data class Joined(val channelId: ChannelId, val deviceId: DeviceId, val epoch: Long) : WsEvent
    data class PresenceUpdate(val channelId: ChannelId, val deviceId: DeviceId, val state: String) : WsEvent
    data class FloorGranted(val channelId: ChannelId, val holder: DeviceId, val leaseExpiresMs: Long) : WsEvent
    data class FloorDenied(
        val channelId: ChannelId,
        val reason: String,
        val holder: DeviceId?,
        val queuePosition: Int?,
    ) : WsEvent
    data class FloorReleased(val channelId: ChannelId, val previousHolder: DeviceId?) : WsEvent
    data class FloorEvent(val channelId: ChannelId, val state: String, val holder: DeviceId?) : WsEvent
    data class Signal(
        val channelId: ChannelId,
        val fromDeviceId: DeviceId,
        val kind: SignalKind,
        val payload: String,
    ) : WsEvent
    data class MediaReceived(val packet: MediaPacket) : WsEvent
    data class KeyEnvelopeReceived(
        val channelId: ChannelId,
        val senderDeviceId: DeviceId,
        val epoch: Long,
        val envelope: ByteArray,
    ) : WsEvent
    enum class ChannelEventKind { MEMBER_JOINED, MEMBER_LEFT, MEMBER_REMOVED, EPOCH_ROTATED }
    data class ChannelEvent(val channelId: ChannelId, val kind: ChannelEventKind, val detail: String) : WsEvent
    data object Pong : WsEvent
    data class Error(val code: String, val message: String) : WsEvent

    enum class SignalKind { OFFER, ANSWER, ICE }
}

object WsProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun encode(command: WsCommand): String {
        val type: String
        val data: JsonObject = when (command) {
            is WsCommand.Join -> {
                type = "join"
                buildJsonObject {
                    put("channel_id", command.channelId.value)
                    put("device_id", command.deviceId.value)
                }
            }
            is WsCommand.FloorRequest -> {
                type = "floor.request"
                buildJsonObject { put("channel_id", command.channelId.value) }
            }
            is WsCommand.FloorRelease -> {
                type = "floor.release"
                buildJsonObject { put("channel_id", command.channelId.value) }
            }
            is WsCommand.FloorOverride -> {
                type = "floor.override"
                buildJsonObject { put("channel_id", command.channelId.value) }
            }
            is WsCommand.SignalOffer -> {
                type = "signaling.offer"
                signalData(command.channelId, command.target, command.sdp, "sdp")
            }
            is WsCommand.SignalAnswer -> {
                type = "signaling.answer"
                signalData(command.channelId, command.target, command.sdp, "sdp")
            }
            is WsCommand.SignalIce -> {
                type = "signaling.ice"
                signalData(command.channelId, command.target, command.ice, "ice")
            }
            is WsCommand.KeyEnvelopeRelay -> {
                type = "keys.envelope"
                buildJsonObject {
                    put("channel_id", command.channelId.value)
                    put("recipient_device_id", command.recipient.value)
                    put("epoch", command.epoch)
                    put("envelope", command.envelopeBase64)
                }
            }
            WsCommand.Ping -> {
                type = "ping"
                JsonObject(emptyMap())
            }
        }
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("type", type)
            put("data", data)
        })
    }

    private fun signalData(channelId: ChannelId, target: DeviceId, payload: String, key: String): JsonObject =
        buildJsonObject {
            put("channel_id", channelId.value)
            put("target_device_id", target.value)
            // Backend relays `sdp` / `ice` verbatim (hub._relay_signaling).
            put(key, payload)
        }

    fun decode(text: String): WsEvent? {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        val data = obj["data"]?.jsonObject ?: JsonObject(emptyMap())
        fun str(key: String): String? = data[key]?.jsonPrimitive?.contentOrNullCompat()
        fun long(key: String): Long? = data[key]?.jsonPrimitive?.contentOrNullCompat()?.toLongOrNull()
        fun int(key: String): Int? = data[key]?.jsonPrimitive?.contentOrNullCompat()?.toIntOrNull()

        return when (type) {
            "joined" -> WsEvent.Joined(
                ChannelId(str("channel_id") ?: return null),
                DeviceId(str("device_id") ?: return null),
                long("epoch") ?: 0L,
            )
            "presence.update" -> WsEvent.PresenceUpdate(
                ChannelId(str("channel_id") ?: return null),
                DeviceId(str("device_id") ?: return null),
                str("state") ?: "offline",
            )
            "floor.granted" -> WsEvent.FloorGranted(
                ChannelId(str("channel_id") ?: return null),
                DeviceId(str("holder_device_id") ?: return null),
                long("lease_expires_ms") ?: 0L,
            )
            "floor.denied" -> WsEvent.FloorDenied(
                ChannelId(str("channel_id") ?: return null),
                str("reason") ?: "unknown",
                str("holder_device_id")?.let(::DeviceId),
                int("queue_position"),
            )
            "floor.released" -> WsEvent.FloorReleased(
                ChannelId(str("channel_id") ?: return null),
                str("previous_holder")?.let(::DeviceId),
            )
            "floor.event" -> WsEvent.FloorEvent(
                ChannelId(str("channel_id") ?: return null),
                str("state") ?: "idle",
                str("holder_device_id")?.let(::DeviceId),
            )
            "signaling.offer" -> signal(data, WsEvent.SignalKind.OFFER, { s -> str(s) }, { d -> DeviceId(d) }, { c -> ChannelId(c) })
            "signaling.answer" -> signal(data, WsEvent.SignalKind.ANSWER, { s -> str(s) }, { d -> DeviceId(d) }, { c -> ChannelId(c) })
            "signaling.ice" -> signal(data, WsEvent.SignalKind.ICE, { s -> str(s) }, { d -> DeviceId(d) }, { c -> ChannelId(c) })
            "keys.envelope" -> WsEvent.KeyEnvelopeReceived(
                ChannelId(str("channel_id") ?: return null),
                DeviceId(str("sender_device_id") ?: return null),
                long("epoch") ?: 0L,
                java.util.Base64.getDecoder().decode(str("envelope") ?: return null),
            )
            "channel.event" -> WsEvent.ChannelEvent(
                ChannelId(str("channel_id") ?: return null),
                channelEventKind(str("event") ?: str("kind")),
                str("detail") ?: "",
            )
            "pong" -> WsEvent.Pong
            "error" -> WsEvent.Error(str("code") ?: "error", str("message") ?: "")
            else -> null
        }
    }

    private fun channelEventKind(raw: String?): WsEvent.ChannelEventKind = when (raw?.lowercase()) {
        "epoch_rotated" -> WsEvent.ChannelEventKind.EPOCH_ROTATED
        "member_joined", "joined" -> WsEvent.ChannelEventKind.MEMBER_JOINED
        "member_left", "left" -> WsEvent.ChannelEventKind.MEMBER_LEFT
        "member_removed", "removed" -> WsEvent.ChannelEventKind.MEMBER_REMOVED
        else -> WsEvent.ChannelEventKind.MEMBER_JOINED
    }

    private fun signal(
        data: JsonObject,
        kind: WsEvent.SignalKind,
        str: (String) -> String?,
        deviceId: (String) -> DeviceId,
        channelId: (String) -> ChannelId,
    ): WsEvent.Signal = WsEvent.Signal(
        channelId(str("channel_id") ?: ""),
        deviceId(str("sender_device_id") ?: str("from_device_id") ?: ""),
        kind,
        // Backend sends sdp/ice, not a generic payload field.
        str("sdp") ?: str("ice") ?: str("payload") ?: "",
    )

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else this.content

    /** Decode a binary media frame to [WsEvent.MediaReceived], or null when malformed. */
    fun decodeMedia(bytes: ByteArray): WsEvent.MediaReceived? =
        runCatching { WsEvent.MediaReceived(MediaPacketCodec.decode(bytes)) }.getOrNull()
}
