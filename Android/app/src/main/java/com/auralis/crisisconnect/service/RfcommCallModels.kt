package com.auralis.crisisconnect.service

import android.media.AudioRecord
import android.media.AudioTrack
import android.media.Ringtone
import android.media.audiofx.AudioEffect
import com.auralis.crisisconnect.service.voice.JitterBuffer
import com.auralis.crisisconnect.service.voice.VoipConfig
import com.auralis.opuscodec.Opus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger

enum class CallState {
    Idle,
    Ringing,
    Connecting,
    InCall,
    Ended
}

enum class CallAudioRoute {
    Earpiece,
    Speaker,
    Bluetooth,
    WiredHeadset,
    Streaming,
    Unknown
}

internal fun orderedCallAudioRoutes(routes: Iterable<CallAudioRoute>): List<CallAudioRoute> {
    return routes
        .distinct()
        .sortedBy { route ->
            when (route) {
                CallAudioRoute.Bluetooth -> 0
                CallAudioRoute.WiredHeadset -> 1
                CallAudioRoute.Earpiece -> 2
                CallAudioRoute.Speaker -> 3
                CallAudioRoute.Streaming -> 4
                CallAudioRoute.Unknown -> 5
            }
        }
}

internal fun defaultCallAudioRoutes(): List<CallAudioRoute> {
    return orderedCallAudioRoutes(
        listOf(
            CallAudioRoute.Earpiece,
            CallAudioRoute.Speaker
        )
    )
}

enum class Profile(
    val jitterCapacity: Int,
    val defaultBitrate: Int,
    val defaultFramesPerPacket: Int
) {
    WB(
        jitterCapacity = 12,
        defaultBitrate = VoipConfig.OPUS_BITRATE_BPS,
        defaultFramesPerPacket = 1
    ),
    NB(
        jitterCapacity = 14,
        defaultBitrate = 12_000,
        defaultFramesPerPacket = 2
    )
}

data class AudioCfg(
    val sr: Int,
    val ch: Int,
    val frm: Int,
    val fps: Int,
    val br: Int,
    val encrypted: Boolean
) {
    val frameSamples: Int = (sr * frm) / 1000
    val frameBytes: Int = frameSamples * ch * 2
}

data class NetStats(
    @Volatile var movingAvgWriteMs: Double = 0.0,
    @Volatile var transportDrops: Int = 0,
    @Volatile var playoutUnderruns: Int = 0,
    @Volatile var avgQueueDepth: Double = 0.0,
    @Volatile var lastAdaptTs: Long = 0L,
    @Volatile var degradeScore: Int = 0,
    @Volatile var recoverScore: Int = 0,
    @Volatile var framesReceived: Int = 0,
    @Volatile var lostFrames: Int = 0,
    @Volatile var outOfOrderFrames: Int = 0,
    @Volatile var duplicateFrames: Int = 0,
    @Volatile var lateDrops: Int = 0,
    @Volatile var invalidPayloadFrames: Int = 0,
    @Volatile var invalidCrcFrames: Int = 0,
    @Volatile var concealedFrames: Int = 0,
    val receiverObservedAgeMs: MutableList<Int> = Collections.synchronizedList(mutableListOf()),
    @Volatile var receiverObservedAgeClampedSamples: Int = 0,
    @Volatile var receiverObservedAgeDiscardedSamples: Int = 0,
    val clockAdjustedAgeMs: MutableList<Int> = Collections.synchronizedList(mutableListOf()),
    @Volatile var clockAdjustedAgeClampedSamples: Int = 0,
    @Volatile var clockAdjustedAgeDiscardedSamples: Int = 0,
    val clockSyncRttMs: MutableList<Int> = Collections.synchronizedList(mutableListOf()),
    val clockSyncOffsetMs: MutableList<Int> = Collections.synchronizedList(mutableListOf()),
    @Volatile var clockSyncDiscardedSamples: Int = 0,
    @Volatile var bestClockSyncRttMs: Double? = null,
    @Volatile var bestRemoteClockOffsetMs: Double? = null
)

internal data class LatencySampleSummary(
    val sampleCount: Int,
    val minMs: Int,
    val medianMs: Int,
    val p95Ms: Int,
    val maxMs: Int,
    val averageMs: Double,
    val clampedSamples: Int,
    val discardedSamples: Int
)

internal data class ClockSyncObservation(
    val remoteClockOffsetMs: Double,
    val rttMs: Double
)

internal data class ClockSyncSummary(
    val sampleCount: Int,
    val bestRttMs: Int,
    val medianRttMs: Int,
    val p95RttMs: Int,
    val bestRemoteClockOffsetMs: Int,
    val medianRemoteClockOffsetMs: Int,
    val discardedSamples: Int
)

internal fun NetStats.recordReceiverObservedAgeSample(
    senderTimestampMs: Long,
    receivedAtMs: Long = System.currentTimeMillis()
) {
    if (senderTimestampMs <= 0L) {
        return
    }
    val observedAgeMs = receivedAtMs - senderTimestampMs
    if (observedAgeMs < -5_000L || observedAgeMs > 60_000L) {
        receiverObservedAgeDiscardedSamples += 1
        return
    }
    if (observedAgeMs < 0L) {
        receiverObservedAgeClampedSamples += 1
    }
    receiverObservedAgeMs += observedAgeMs.coerceAtLeast(0L).toInt()
    capSampleList(receiverObservedAgeMs)
}

internal fun NetStats.clearReceiverObservedAgeSamples() {
    receiverObservedAgeMs.clear()
    receiverObservedAgeClampedSamples = 0
    receiverObservedAgeDiscardedSamples = 0
}

internal fun NetStats.receiverObservedAgeSummary(): LatencySampleSummary? {
    val snapshot = synchronized(receiverObservedAgeMs) { receiverObservedAgeMs.toList() }
    if (snapshot.isEmpty()) {
        return null
    }
    val sorted = snapshot.sorted()
    return LatencySampleSummary(
        sampleCount = sorted.size,
        minMs = sorted.first(),
        medianMs = sorted.percentile(p = 0.5),
        p95Ms = sorted.percentile(p = 0.95),
        maxMs = sorted.last(),
        averageMs = sorted.average(),
        clampedSamples = receiverObservedAgeClampedSamples,
        discardedSamples = receiverObservedAgeDiscardedSamples
    )
}

internal fun NetStats.recordClockAdjustedAgeSample(
    senderMonotonicTimestampMs: Long,
    receivedAtMs: Long,
    remoteClockOffsetMs: Double? = bestRemoteClockOffsetMs
) {
    if (senderMonotonicTimestampMs <= 0L || remoteClockOffsetMs == null) {
        return
    }
    val adjustedAgeMs = receivedAtMs - (senderMonotonicTimestampMs - remoteClockOffsetMs)
    if (adjustedAgeMs < -250.0 || adjustedAgeMs > 10_000.0) {
        clockAdjustedAgeDiscardedSamples += 1
        return
    }
    if (adjustedAgeMs < 0.0) {
        clockAdjustedAgeClampedSamples += 1
    }
    clockAdjustedAgeMs += adjustedAgeMs.coerceAtLeast(0.0).roundToInt()
    capSampleList(clockAdjustedAgeMs)
}

internal fun NetStats.clearClockAdjustedAgeSamples() {
    clockAdjustedAgeMs.clear()
    clockAdjustedAgeClampedSamples = 0
    clockAdjustedAgeDiscardedSamples = 0
}

internal fun NetStats.clockAdjustedAgeSummary(): LatencySampleSummary? {
    val snapshot = synchronized(clockAdjustedAgeMs) { clockAdjustedAgeMs.toList() }
    if (snapshot.isEmpty()) {
        return null
    }
    val sorted = snapshot.sorted()
    return LatencySampleSummary(
        sampleCount = sorted.size,
        minMs = sorted.first(),
        medianMs = sorted.percentile(p = 0.5),
        p95Ms = sorted.percentile(p = 0.95),
        maxMs = sorted.last(),
        averageMs = sorted.average(),
        clampedSamples = clockAdjustedAgeClampedSamples,
        discardedSamples = clockAdjustedAgeDiscardedSamples
    )
}

internal fun computeClockSyncObservation(
    requestSentAtMs: Long,
    remoteReceivedAtMs: Long,
    remoteSentAtMs: Long,
    responseReceivedAtMs: Long
): ClockSyncObservation? {
    if (
        requestSentAtMs <= 0L ||
        remoteReceivedAtMs <= 0L ||
        remoteSentAtMs <= 0L ||
        responseReceivedAtMs <= 0L
    ) {
        return null
    }
    val rttMs = (responseReceivedAtMs - requestSentAtMs) - (remoteSentAtMs - remoteReceivedAtMs)
    if (rttMs < 0L || rttMs > 5_000L) {
        return null
    }
    val remoteClockOffsetMs =
        ((remoteReceivedAtMs - requestSentAtMs).toDouble() + (remoteSentAtMs - responseReceivedAtMs).toDouble()) / 2.0
    return ClockSyncObservation(
        remoteClockOffsetMs = remoteClockOffsetMs,
        rttMs = rttMs.toDouble()
    )
}

internal fun NetStats.recordClockSyncObservation(observation: ClockSyncObservation): Boolean {
    // Monotonic clocks on different devices use unrelated boot-time origins, so a
    // large absolute offset is expected and not by itself invalid.
    if (!observation.remoteClockOffsetMs.isFinite() || !observation.rttMs.isFinite() || observation.rttMs < 0.0) {
        clockSyncDiscardedSamples += 1
        return false
    }
    val rttMs = observation.rttMs.roundToInt()
    val offsetMs = observation.remoteClockOffsetMs.roundToInt()
    clockSyncRttMs += rttMs
    capSampleList(clockSyncRttMs)
    clockSyncOffsetMs += offsetMs
    capSampleList(clockSyncOffsetMs)
    val bestRtt = bestClockSyncRttMs
    if (bestRtt == null || observation.rttMs <= bestRtt) {
        bestClockSyncRttMs = observation.rttMs
        bestRemoteClockOffsetMs = observation.remoteClockOffsetMs
    }
    return true
}

internal fun NetStats.clearClockSyncSamples() {
    clockSyncRttMs.clear()
    clockSyncOffsetMs.clear()
    clockSyncDiscardedSamples = 0
    bestClockSyncRttMs = null
    bestRemoteClockOffsetMs = null
}

internal fun NetStats.clockSyncSummary(): ClockSyncSummary? {
    val rttSnapshot = synchronized(clockSyncRttMs) { clockSyncRttMs.toList() }
    val offsetSnapshot = synchronized(clockSyncOffsetMs) { clockSyncOffsetMs.toList() }
    if (rttSnapshot.isEmpty() || offsetSnapshot.isEmpty()) {
        return null
    }
    val sortedRtt = rttSnapshot.sorted()
    val sortedOffset = offsetSnapshot.sorted()
    val bestRttMs = bestClockSyncRttMs?.roundToInt() ?: sortedRtt.first()
    val bestRemoteClockOffsetMs = bestRemoteClockOffsetMs?.roundToInt() ?: sortedOffset.first()
    return ClockSyncSummary(
        sampleCount = minOf(sortedRtt.size, sortedOffset.size),
        bestRttMs = bestRttMs,
        medianRttMs = sortedRtt.percentile(p = 0.5),
        p95RttMs = sortedRtt.percentile(p = 0.95),
        bestRemoteClockOffsetMs = bestRemoteClockOffsetMs,
        medianRemoteClockOffsetMs = sortedOffset.percentile(p = 0.5),
        discardedSamples = clockSyncDiscardedSamples
    )
}

private const val MAX_LATENCY_SAMPLES = 5_000

private fun capSampleList(list: MutableList<Int>) {
    if (list.size > MAX_LATENCY_SAMPLES) {
        synchronized(list) {
            val excess = list.size - MAX_LATENCY_SAMPLES
            if (excess > 0) {
                list.subList(0, excess).clear()
            }
        }
    }
}

private fun List<Int>.percentile(p: Double): Int {
    if (isEmpty()) {
        return 0
    }
    if (size == 1) {
        return first()
    }
    val clampedP = p.coerceIn(0.0, 1.0)
    val position = (lastIndex * clampedP)
    val lowerIndex = position.toInt()
    val upperIndex = kotlin.math.ceil(position).toInt()
    if (lowerIndex == upperIndex) {
        return this[lowerIndex]
    }
    val fraction = position - lowerIndex
    val interpolated = this[lowerIndex] + (this[upperIndex] - this[lowerIndex]) * fraction
    return interpolated.roundToInt()
}

internal fun Profile.defaultCfg(encrypted: Boolean): AudioCfg = when (this) {
    Profile.WB -> AudioCfg(
        sr = VoipConfig.SAMPLE_RATE_HZ,
        ch = 1,
        frm = VoipConfig.FRAME_DURATION_MS,
        fps = defaultFramesPerPacket,
        br = defaultBitrate,
        encrypted = encrypted
    )

    Profile.NB -> AudioCfg(
        sr = VoipConfig.SAMPLE_RATE_HZ,
        ch = 1,
        frm = VoipConfig.FRAME_DURATION_MS,
        fps = defaultFramesPerPacket,
        br = defaultBitrate,
        encrypted = encrypted
    )
}

data class CallUiState(
    val sessionCode: String,
    val callId: String,
    val state: CallState,
    val isOutgoing: Boolean,
    val encrypted: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val frameMs: Int,
    val bitrate: Int,
    val startedAt: Long,
    val connectedAt: Long?,
    val muted: Boolean,
    val speakerEnabled: Boolean,
    val currentRoute: CallAudioRoute,
    val availableRoutes: List<CallAudioRoute>,
    val profile: Profile,
    val jitterCapacity: Int
)

internal data class CallSession(
    val sessionCode: String,
    val callId: String,
    val aesKey: ByteArray?,
    val isOutgoing: Boolean,
    val state: MutableStateFlow<CallState> = MutableStateFlow(CallState.Idle),
    val muted: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val speakerEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val startedAt: Long = System.currentTimeMillis(),
    val outboundBuilder: StringBuilder = StringBuilder(256),
    val outboundCrc: java.util.zip.CRC32 = java.util.zip.CRC32(),
    val netStats: NetStats = NetStats()
) {
    @Volatile
    var profile: Profile = Profile.WB

    @Volatile
    var cfg: AudioCfg = profile.defaultCfg(encrypted = aesKey != null)

    @Volatile
    var jitterBuffer: JitterBuffer = JitterBuffer()

    val seq: AtomicInteger = AtomicInteger(0)

    @Volatile
    var opus: Opus? = null

    @Volatile
    var recorder: AudioRecord? = null

    @Volatile
    var player: AudioTrack? = null

    @Volatile
    var senderJob: Job? = null

    @Volatile
    var playerJob: Job? = null

    @Volatile
    var adaptationJob: Job? = null

    @Volatile
    var syncJob: Job? = null

    @Volatile
    var ringtone: Ringtone? = null

    @Volatile
    var timeout: Runnable? = null

    @Volatile
    var connectedAt: Long? = null

    @Volatile
    var currentRoute: CallAudioRoute = CallAudioRoute.Earpiece

    @Volatile
    var availableRoutes: List<CallAudioRoute> = defaultCallAudioRoutes()

    @Volatile
    var remoteDisplayName: String? = null

    @Volatile
    var frameSizeConst: Int? = null

    @Volatile
    var lastInboundSeq: Int = -1

    @Volatile
    var lastPlayoutFrame: ByteArray? = null

    @Volatile
    var audioEffects: List<AudioEffect> = emptyList()

    @Volatile
    var pendingProfile: Profile? = null

    @Volatile
    var pendingCfg: AudioCfg? = null

    @Volatile
    var awaitingAck: Boolean = false

    @Volatile
    var audioSessionAcquired: Boolean = false

    val pendingClockSyncProbes: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
}

internal data class CallNotificationSummary(
    val direction: RfcommForegroundService.CallDirection,
    val result: RfcommForegroundService.CallResult,
    val durationMillis: Long?
)

internal data class CallRuntime(
    val callId: String,
    val sessionCode: String,
    val startedAt: Long,
    var answeredAt: Long? = null,
    var endedAt: Long? = null,
    var direction: RfcommForegroundService.CallDirection,
    var result: RfcommForegroundService.CallResult? = null
)
