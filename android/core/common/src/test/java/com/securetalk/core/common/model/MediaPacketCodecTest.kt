package com.securetalk.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPacketCodecTest {

    @Test
    fun `round trip uuid to bytes`() {
        val id = DeviceId("123e4567-e89b-12d3-a456-426614174000")
        val bytes = MediaPacketCodec.deviceIdToBytes(id)
        assertEquals(16, bytes.size)
        assertEquals(id, MediaPacketCodec.deviceIdFromBytes(bytes))
    }

    @Test
    fun `round trip packet`() {
        val packet = MediaPacket(
            epoch = 7,
            sender = DeviceId("123e4567-e89b-12d3-a456-426614174000"),
            sequence = 0x12345678L,
            payload = ByteArray(97) { it.toByte() },
        )
        val encoded = MediaPacketCodec.encode(packet)
        assertEquals(MediaPacketCodec.HEADER_SIZE + 97, encoded.size)
        val decoded = MediaPacketCodec.decode(encoded)
        assertEquals(packet, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short frame rejected`() {
        MediaPacketCodec.decode(ByteArray(10))
    }
}
