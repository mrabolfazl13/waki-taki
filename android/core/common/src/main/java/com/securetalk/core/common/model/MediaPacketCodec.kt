package com.securetalk.core.common.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire codec for the binary media header (ARCHITECTURE §6):
 *
 * ```
 * epoch(4 BE) | sender_device_id(16) | sequence(4 BE) | ciphertext+tag
 * ```
 *
 * Device IDs are UUIDs; the 16-byte sender field is the UUID's hex digits
 * (32 hex chars -> 16 bytes, hyphens stripped) so relays can route without
 * parsing anything beyond the fixed header.
 */
object MediaPacketCodec {

    const val HEADER_SIZE = 4 + 16 + 4
    private const val HEX = "0123456789abcdef"

    fun encode(packet: MediaPacket): ByteArray {
        val sender = deviceIdToBytes(packet.sender)
        val out = ByteArray(HEADER_SIZE + packet.payload.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(packet.epoch)
        buf.put(sender)
        buf.putInt(packet.sequence.toInt())
        System.arraycopy(packet.payload, 0, out, HEADER_SIZE, packet.payload.size)
        return out
    }

    fun decode(bytes: ByteArray): MediaPacket {
        require(bytes.size > HEADER_SIZE) { "media frame too short: ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val epoch = buf.int
        val senderBytes = ByteArray(16)
        buf.get(senderBytes)
        val seq = buf.int.toLong() and 0xFFFFFFFFL
        val payload = ByteArray(bytes.size - HEADER_SIZE)
        System.arraycopy(bytes, HEADER_SIZE, payload, 0, payload.size)
        return MediaPacket(epoch, deviceIdFromBytes(senderBytes), seq, payload)
    }

    fun deviceIdToBytes(id: DeviceId): ByteArray {
        val hex = id.value.lowercase().replace("-", "")
        require(hex.length == 32) { "device id must be a UUID string, got: ${id.value}" }
        return ByteArray(16) { i ->
            ((HEX.indexOf(hex[2 * i]) shl 4) or HEX.indexOf(hex[2 * i + 1])).toByte()
        }
    }

    fun deviceIdFromBytes(bytes: ByteArray): DeviceId {
        require(bytes.size == 16) { "device id bytes must be 16" }
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i in intArrayOf(4, 6, 8, 10)) sb.append('-')
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return DeviceId(sb.toString())
    }
}
