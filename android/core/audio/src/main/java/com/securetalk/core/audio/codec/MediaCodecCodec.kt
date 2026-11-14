package com.securetalk.core.audio.codec

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/**
 * MediaCodec-backed encoder/decoder for Opus (API 29+) with an AAC-LC fallback
 * for API 26-28 (see [AudioCodec] docs and android/README.md).
 *
 * Synchronous MediaCodec API throughout — no experimental APIs.
 */
object MediaCodecCodec {

    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS // 960

    /** Picks the best codec available on this device. */
    fun create(): AudioCodec =
        if (Build.VERSION.SDK_INT >= 29 && hasCodec("audio/opus", isEncoder = true) && hasCodec("audio/opus", isEncoder = false)) {
            MediaCodecAudioCodec(opusFormat(), opusDecodeFormat(), "opus")
        } else {
            MediaCodecAudioCodec(aacEncodeFormat(), aacDecodeFormat(), "aac-lc")
        }

    private fun hasCodec(mime: String, isEncoder: Boolean): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            info.isEncoder == isEncoder && info.supportedTypes.contains(mime)
        }
    }

    // ---- Opus ---------------------------------------------------------------------------

    private fun opusFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, CHANNELS).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        if (Build.VERSION.SDK_INT >= 30) {
            setInteger(MediaFormat.KEY_COMPLEXITY, 5)
        }
    }

    private fun opusDecodeFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, CHANNELS).apply {
        // Minimal OpusHead as csd-0 (19 bytes) so the decoder accepts raw packets.
        val head = opusHead()
        setByteBuffer("csd-0", ByteBuffer.wrap(head))
    }

    private fun opusHead(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("OpusHead".toByteArray(Charsets.US_ASCII))
        out.write(1)                    // version
        out.write(CHANNELS)             // channel count
        val preskip = 312               // standard MediaCodec opus preskip
        out.write(preskip and 0xFF)
        out.write((preskip shr 8) and 0xFF)
        out.write(SAMPLE_RATE and 0xFF) // input sample rate, LE 32-bit
        out.write((SAMPLE_RATE shr 8) and 0xFF)
        out.write((SAMPLE_RATE shr 16) and 0xFF)
        out.write((SAMPLE_RATE shr 24) and 0xFF)
        out.write(0); out.write(0)      // output gain
        out.write(0)                    // mapping family (mono default)
        return out.toByteArray()
    }

    // ---- AAC-LC fallback -----------------------------------------------------------------

    private fun aacEncodeFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        setByteBuffer("csd-0", ByteBuffer.wrap(aacSpecificConfig()))
    }

    private fun aacDecodeFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(aacSpecificConfig()))
    }

    /** AudioSpecificConfig for AAC-LC (AOT=2), 48 kHz, mono. */
    private fun aacSpecificConfig(): ByteArray {
        val sampleRateIndex = 3 // 48 kHz per ISO 14496-3 table
        val config = (2 shl 11) or (sampleRateIndex shl 7) or (CHANNELS shl 3)
        return byteArrayOf(
            ((config shr 8) and 0xFF).toByte(),
            (config and 0xFF).toByte(),
        )
    }
}

/**
 * Symmetric encoder/decoder pair over synchronous MediaCodec.
 * One instance owns both an encoder and a decoder.
 */
class MediaCodecAudioCodec(
    private val encodeFormat: MediaFormat,
    private val decodeFormat: MediaFormat,
    override val name: String,
) : AudioCodec {

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val outBufferInfo = MediaCodec.BufferInfo()

    override fun start() {
        release()
        encoder = MediaCodec.createEncoderByType(encodeFormat.getString(MediaFormat.KEY_MIME) ?: "audio/opus").apply {
            configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        decoder = MediaCodec.createDecoderByType(decodeFormat.getString(MediaFormat.KEY_MIME) ?: "audio/opus").apply {
            configure(decodeFormat, null, null, 0)
            start()
        }
    }

    override fun encode(pcmFrame: ShortArray): ByteArray {
        val enc = encoder ?: throw IllegalStateException("encoder not started")
        val pcmBytes = ByteArray(pcmFrame.size * 2)
        for (i in pcmFrame.indices) {
            val v = pcmFrame[i].toInt()
            pcmBytes[2 * i] = (v and 0xFF).toByte()
            pcmBytes[2 * i + 1] = (v shr 8).toByte()
        }

        val inIndex = enc.dequeueInputBuffer(10_000)
        if (inIndex < 0) return ByteArray(0)
        val inBuf = enc.getInputBuffer(inIndex) ?: return ByteArray(0)
        inBuf.clear()
        val toCopy = minOf(inBuf.remaining(), pcmBytes.size)
        inBuf.put(pcmBytes, 0, toCopy)
        enc.queueInputBuffer(inIndex, 0, toCopy, 0, 0)

        val outIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000)
        return when {
            outIndex >= 0 -> {
                val buf = enc.getOutputBuffer(outIndex)
                val out = if (buf == null) ByteArray(0) else {
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    ByteArray(bufferInfo.size).also { buf.get(it) }
                }
                enc.releaseOutputBuffer(outIndex, false)
                out
            }
            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER || outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> ByteArray(0)
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> ByteArray(0)
            else -> ByteArray(0)
        }
    }

    override fun decode(encoded: ByteArray): ShortArray {
        val dec = decoder ?: throw IllegalStateException("decoder not started")
        val inIndex = dec.dequeueInputBuffer(10_000)
        if (inIndex < 0) return ShortArray(0)
        val inBuf = dec.getInputBuffer(inIndex) ?: return ShortArray(0)
        inBuf.clear()
        val toCopy = minOf(inBuf.remaining(), encoded.size)
        inBuf.put(encoded, 0, toCopy)
        dec.queueInputBuffer(inIndex, 0, toCopy, 0, 0)

        val outIndex = dec.dequeueOutputBuffer(outBufferInfo, 10_000)
        if (outIndex < 0) return ShortArray(0)
        val buf = dec.getOutputBuffer(outIndex) ?: return ShortArray(0)
        val size = outBufferInfo.size / 2
        val out = ShortArray(size)
        buf.position(outBufferInfo.offset)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        dec.releaseOutputBuffer(outIndex, false)
        return out
    }

    override fun flush() {
        encoder?.flush()
        decoder?.flush()
    }

    override fun release() {
        encoder?.stop()
        encoder?.release()
        encoder = null
        decoder?.stop()
        decoder?.release()
        decoder = null
    }
}
