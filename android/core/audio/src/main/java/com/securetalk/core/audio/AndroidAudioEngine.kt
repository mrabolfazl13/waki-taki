package com.securetalk.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.securetalk.core.audio.codec.AudioCodec
import com.securetalk.core.audio.codec.MediaCodecCodec
import com.securetalk.core.audio.jitter.JitterBuffer
import com.securetalk.core.common.domain.AudioEngine
import com.securetalk.core.common.model.AudioFrame
import com.securetalk.core.common.model.AudioRoute
import com.securetalk.core.common.model.ChannelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AudioEngine] over android.media:
 *  - Capture: AudioRecord, VOICE_COMMUNICATION source (AEC/NS in the audio path),
 *    plus explicit AcousticEchoCanceler / NoiseSuppressor when available.
 *  - Playback: AudioTrack PERFORMANCE_MODE_LOW_LATENCY, USAGE_VOICE_COMMUNICATION.
 *  - Codec: MediaCodec Opus on API 29+, AAC-LC fallback on 26-28 (see AudioCodec docs).
 *  - Jitter buffer: adaptive, bounded at 400 ms; PLC = silence frame + plc flag.
 *  - Routing: [AudioRouter] over AudioManager communication devices + device callback.
 */
class AndroidAudioEngine(
    context: Context,
    scope: CoroutineScope? = null,
) : AudioEngine {

    private val appContext = context.applicationContext
    private val engineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val router = AudioRouter(audioManager)

    private val frameSamples = MediaCodecCodec.FRAME_SAMPLES

    private val _captureFrames = MutableSharedFlow<AudioFrame>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val captureFrames: SharedFlow<AudioFrame> = _captureFrames.asSharedFlow()

    private val _route = MutableStateFlow(AudioRoute.EARPIECE)
    override val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private val _routeOptions = MutableStateFlow<List<AudioRoute>>(emptyList())
    override val routeOptions: StateFlow<List<AudioRoute>> = _routeOptions.asStateFlow()

    private val capturing = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null

    private var track: AudioTrack? = null
    private var playbackJob: Job? = null
    private var activePlaybackChannel: ChannelId? = null
    private val jitterBuffers = ConcurrentHashMap<String, JitterBuffer<ByteArray>>()
    private val plcCounters = ConcurrentHashMap<String, Long>()

    private var codec: AudioCodec? = null
    private var unregisterRouter: (() -> Unit)? = null

    override suspend fun startCapture(): Result<Unit> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            if (capturing.get()) return@withContext Result.success(Unit)

            val minBuf = AudioRecord.getMinBufferSize(
                MediaCodecCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuf > 0) { "AudioRecord min buffer invalid" }

            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MediaCodecCodec.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, frameSamples * 2 * 10))
                .build()
            require(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized (mic permission?)" }

            record = rec
            val session = rec.audioSessionId
            if (session != 0) {
                if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(session)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(session)?.apply { enabled = true }
            }

            if (codec == null) codec = MediaCodecCodec.create()
            codec?.start()

            capturing.set(true)
            captureJob = engineScope.launch { captureLoop(rec) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun captureLoop(rec: AudioRecord) {
        val buffer = ShortArray(frameSamples)
        rec.startRecording()
        while (engineScope.isActive && capturing.get()) {
            val read = rec.read(buffer, 0, frameSamples)
            if (read <= 0) {
                kotlinx.coroutines.delay(5)
                continue
            }
            val frame = AudioFrame(buffer.copyOf(read), MediaCodecCodec.SAMPLE_RATE)
            _captureFrames.tryEmit(frame)
        }
        rec.stop()
    }

    override suspend fun stopCapture() {
        capturing.set(false)
        captureJob?.cancel()
        captureJob = null
        echoCanceler?.release()
        noiseSuppressor?.release()
        echoCanceler = null
        noiseSuppressor = null
        record?.release()
        record = null
        codec?.flush()
    }

    override suspend fun startPlayback(channelId: ChannelId) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            stopPlaybackInternal()
            jitterBuffers.getOrPut(channelId.value) { JitterBuffer() }.clear()
            activePlaybackChannel = channelId

            val minBuf = AudioTrack.getMinBufferSize(
                MediaCodecCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MediaCodecCodec.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 2, frameSamples * 2 * 8))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            newTrack.play()
            track = newTrack

            if (codec == null) codec = MediaCodecCodec.create()
            codec?.start()

            playbackJob = engineScope.launch { playbackLoop(channelId) }
        }
    }

    private suspend fun playbackLoop(channelId: ChannelId) {
        val t = track ?: return
        val silence = ShortArray(frameSamples)
        while (engineScope.isActive && activePlaybackChannel == channelId && t.state == AudioTrack.STATE_INITIALIZED) {
            val encoded = jitterBuffers[channelId.value]?.pop()
            val pcm: ShortArray = if (encoded == null) {
                // Underflow: packet-loss concealment with a silence frame (plc flag counted).
                plcCounters.merge(channelId.value, 1L, Long::plus)
                silence
            } else {
                val decoded = codec?.decode(encoded) ?: silence
                if (decoded.isEmpty()) silence else decoded
            }
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            kotlinx.coroutines.delay(1)
        }
    }

    override suspend fun stopPlayback() {
        kotlinx.coroutines.withContext(Dispatchers.IO) { stopPlaybackInternal() }
    }

    private fun stopPlaybackInternal() {
        playbackJob?.cancel()
        playbackJob = null
        activePlaybackChannel = null
        track?.pause()
        track?.release()
        track = null
        jitterBuffers.values.forEach { it.clear() }
    }

    override fun submitEncoded(channelId: ChannelId, encodedPayload: ByteArray, plc: Boolean) {
        val jitter = jitterBuffers.getOrPut(channelId.value) { JitterBuffer() }
        jitter.push(encodedPayload)
    }

    override suspend fun setRoute(newRoute: AudioRoute) {
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            router.setRoute(newRoute)
            _route.value = router.currentRoute()
        }
    }

    fun start() {
        _route.value = router.currentRoute()
        _routeOptions.value = router.availableRoutes()
        unregisterRouter = router.registerDeviceChangedListener {
            _routeOptions.value = router.availableRoutes()
        }
    }

    override fun release() {
        capturing.set(false)
        captureJob?.cancel()
        playbackJob?.cancel()
        echoCanceler?.release()
        noiseSuppressor?.release()
        record?.release()
        track?.release()
        codec?.release()
        record = null
        track = null
        codec = null
        echoCanceler = null
        noiseSuppressor = null
        unregisterRouter?.invoke()
        unregisterRouter = null
        jitterBuffers.clear()
        engineScope.cancel()
    }
}
