package com.auralis.crisisconnect.service.p2p.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.service.voice.JitterBuffer
import com.auralis.crisisconnect.service.voice.VoipConfig
import com.auralis.opuscodec.Opus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-duplex Opus audio engine for voice calls over the P2P GATT link. Derived from the telsiz
 * [com.auralis.crisisconnect.service.gattmesh.ptt.PttAudioEngine], but capture and playback run
 * at the same time (a single [Opus] instance drives both the encoder and the decoder, exactly like
 * the RFCOMM `CallSession` does).
 */
internal class P2pCallAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val sampleRate = VoipConfig.SAMPLE_RATE_HZ
    private val channels = 1
    private val frameSamples = sampleRate * VoipConfig.FRAME_DURATION_MS / 1000 // 320
    private val frameBytes = frameSamples * channels * 2 // 640 (PCM16 mono)
    private val opusApplicationVoip = 2048

    private var opus: Opus? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var playerJob: Job? = null
    private var audioEffects: List<AudioEffect> = emptyList()

    @Volatile
    private var jitter: JitterBuffer? = null

    @Volatile
    var muted: Boolean = false

    @Synchronized
    fun prepare(bitrateBps: Int): Boolean {
        if (opus != null) return true
        val instance = runCatching { Opus() }.getOrElse {
            Log.e(TAG, "Opus native library unavailable", it)
            return false
        }
        if (instance.encoderInit(sampleRate, channels, opusApplicationVoip) != 0) return false
        if (instance.decoderInit(sampleRate, channels) != 0) return false
        runCatching { instance.encoderSetComplexity(VoipConfig.OPUS_COMPLEXITY) }
        runCatching { instance.encoderSetBitrate(bitrateBps) }
        opus = instance
        return true
    }

    fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** Applies a new Opus encoder bitrate mid-call (receiver-driven adaptation). */
    fun setBitrate(bitrateBps: Int) {
        runCatching { opus?.encoderSetBitrate(bitrateBps) }
    }

    /**
     * Start capturing the microphone, encoding each 20 ms frame to Opus and handing the bytes to
     * [onFrame]. While [muted], silence is encoded instead of dropping frames so the remote jitter
     * buffer keeps a continuous stream. No-op if already capturing.
     */
    @Synchronized
    fun startCapture(onFrame: (ByteArray) -> Unit): Boolean {
        if (captureJob != null) return true
        if (!hasRecordPermission()) return false
        val encoder = opus ?: return false
        val mic = createRecorder() ?: return false
        recorder = mic
        audioEffects = enableAudioEffects(mic)
        runCatching { mic.startRecording() }
        if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { mic.release() }
            recorder = null
            return false
        }
        captureJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ByteArray(frameBytes)
            while (isActive) {
                var read = 0
                while (read < frameBytes) {
                    val n = mic.read(pcm, read, frameBytes - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frameBytes) continue
                if (muted) {
                    pcm.fill(0)
                }
                val encoded = runCatching { encoder.encode(pcm, frameSamples) }.getOrNull()
                if (encoded != null && encoded.isNotEmpty()) {
                    onFrame(encoded)
                }
            }
        }
        return true
    }

    @Synchronized
    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        audioEffects.forEach { runCatching { it.release() } }
        audioEffects = emptyList()
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
    }

    private fun enableAudioEffects(recorder: AudioRecord): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>(3)
        fun attach(effect: AudioEffect?) {
            if (effect == null) return
            runCatching { effect.enabled = true }
            effects.add(effect)
        }
        val sessionId = recorder.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            attach(runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull())
        }
        if (NoiseSuppressor.isAvailable()) {
            attach(runCatching { NoiseSuppressor.create(sessionId) }.getOrNull())
        }
        if (AutomaticGainControl.isAvailable()) {
            attach(runCatching { AutomaticGainControl.create(sessionId) }.getOrNull())
        }
        return effects
    }

    /** Begin playback; resets the jitter buffer. No-op if already playing. */
    @Synchronized
    fun startPlayback(): Boolean {
        if (playerJob != null) return true
        val track = createTrack() ?: return false
        player = track
        val buffer = JitterBuffer(
            initialTargetDelayMs = VoipConfig.JITTER_DEFAULT_DELAY_MS,
            maxBufferedFrames = (VoipConfig.JITTER_MAX_DELAY_MS / VoipConfig.FRAME_DURATION_MS) + 4
        )
        jitter = buffer
        runCatching { track.play() }
        val silence = ByteArray(frameBytes)
        playerJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var lastPlayed: ByteArray? = null
            var concealedInARow = 0
            while (isActive) {
                if (!buffer.shouldStartPlayback()) {
                    kotlinx.coroutines.delay(VoipConfig.FRAME_DURATION_MS.toLong())
                    continue
                }
                val frame = buffer.pollFrame()
                // Conceal short gaps with the previous frame, but fall back to silence quickly:
                // during long pauses (remote muted / link stall) repeating the last frame loops
                // audible artifacts.
                val data = when {
                    frame != null -> frame
                    lastPlayed != null && concealedInARow < MAX_CONCEALED_REPEATS -> lastPlayed
                    else -> silence
                }
                concealedInARow = if (frame == null) concealedInARow + 1 else 0
                runCatching { track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING) }
                if (frame != null) lastPlayed = frame
            }
        }
        return true
    }

    /** Decode an incoming Opus frame and enqueue the PCM into the jitter buffer for playback. */
    fun submitFrame(seq: Int, opusBytes: ByteArray) {
        val decoder = opus ?: return
        val buffer = jitter ?: return
        val pcm = runCatching { decoder.decode(opusBytes, frameSamples, 0) }.getOrNull() ?: return
        if (pcm.isNotEmpty()) {
            buffer.addFrame(pcm, seq)
        }
    }

    @Synchronized
    fun stopPlayback() {
        playerJob?.cancel()
        playerJob = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        jitter?.reset()
        jitter = null
    }

    @Synchronized
    fun release() {
        stopCapture()
        stopPlayback()
        muted = false
        opus = null
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        if (!hasRecordPermission()) return null
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer * 2, frameBytes * 4)
        val mic = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .setSampleRate(sampleRate)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }.getOrNull() ?: return null
        return if (mic.state == AudioRecord.STATE_INITIALIZED) {
            mic
        } else {
            runCatching { mic.release() }
            null
        }
    }

    private fun createTrack(): AudioTrack? {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer * 2, frameBytes * 4)
        val track = runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        }.getOrNull() ?: return null
        return if (track.state == AudioTrack.STATE_INITIALIZED) {
            track
        } else {
            runCatching { track.release() }
            null
        }
    }

    private companion object {
        private const val TAG = "P2pCallAudioEngine"
        private const val MAX_CONCEALED_REPEATS = 2
    }
}
