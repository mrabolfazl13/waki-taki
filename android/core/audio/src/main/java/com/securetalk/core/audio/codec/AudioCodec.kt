package com.securetalk.core.audio.codec

/**
 * Codec abstraction: 20 ms frames of 16-bit mono PCM at 48 kHz.
 *
 * DECISION (documented in android/README.md):
 *  - API 29+: hardware/vendor "audio/opus" MediaCodec encoder + decoder.
 *  - API 26-28: "audio/opus" encoder is not exposed by MediaCodec, so we fall
 *    back to AAC-LC ("audio/mp4a-latm") via MediaCodec, symmetric on both ends.
 *    All members of a channel negotiate the codec through the channel epoch info,
 *    so mixed deployments are avoided.
 */
interface AudioCodec {
    val name: String
    fun start()
    fun encode(pcmFrame: ShortArray): ByteArray
    fun decode(encoded: ByteArray): ShortArray
    fun flush()
    fun release()
}
