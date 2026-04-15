package com.auralis.crisisconnect.service

import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.util.Log
import com.auralis.crisisconnect.service.voice.JitterBuffer
import com.auralis.crisisconnect.service.voice.VoipConfig
import com.auralis.opuscodec.Opus
import kotlinx.coroutines.Job

internal fun resolveCallConnectedAt(existingConnectedAt: Long?, nowMillis: Long): Long {
    return existingConnectedAt ?: nowMillis
}

internal class RfcommCallAudioPipelineDelegate(
    private val opusSampleRate: (Int) -> Int?,
    private val opusChannels: (Int) -> Int?,
    private val opusFrameSize: (Int) -> Int?,
    private val releaseAudioEffects: (CallSession) -> Unit,
    private val resolveOpusApplication: () -> Int,
    private val setEncoderBitrate: (Opus, Int) -> Unit,
    private val enableOpusControls: (Opus, Int) -> Unit,
    private val ensureCallAudioSession: (CallSession) -> Boolean,
    private val createAudioRecorder: (AudioCfg) -> AudioRecord?,
    private val createAudioTrack: (AudioCfg) -> AudioTrack?,
    private val releaseCallAudioSession: (CallSession) -> Unit,
    private val updatePlayerOutputRoute: (AudioTrack?, CallAudioRoute) -> Unit,
    private val setPlayerGain: (AudioTrack) -> Unit,
    private val enableAudioEffects: (AudioRecord) -> List<AudioEffect>,
    private val startSenderJob: (CallSession, RfcommForegroundService.ConnectedThread) -> Job,
    private val startPlayerJob: (CallSession) -> Job,
    private val startAdaptationJob: (CallSession, RfcommForegroundService.ConnectedThread?) -> Job,
    private val startClockSyncJob: (CallSession, RfcommForegroundService.ConnectedThread?) -> Job,
    private val markCallAnswered: (String, String, Long) -> Unit,
    private val publishCallState: (CallSession) -> Unit
) {
    fun prepareAudioPipelines(
        session: CallSession,
        thread: RfcommForegroundService.ConnectedThread
    ): Boolean {
        val cfg = session.cfg
        val sampleRateConst = opusSampleRate(cfg.sr) ?: return false
        val channelConst = opusChannels(cfg.ch) ?: return false
        val frameConst = opusFrameSize(cfg.frameSamples) ?: return false
        session.frameSizeConst = frameConst

        session.senderJob?.cancel()
        session.playerJob?.cancel()
        session.adaptationJob?.cancel()
        session.syncJob?.cancel()
        releaseAudioEffects(session)

        // The bundled easyopus AAR does not export encoderRelease/decoderRelease JNI symbols.
        // Avoid calling the missing methods until the native dependency is replaced.
        session.opus = null

        val opus = runCatching { Opus() }.getOrElse {
            Log.e(TAG, "Opus native library unavailable", it)
            return false
        }
        val applicationConst = resolveOpusApplication()
        if (opus.encoderInit(sampleRateConst, channelConst, applicationConst) != 0) {
            return false
        }
        if (opus.decoderInit(sampleRateConst, channelConst) != 0) {
            return false
        }
        runCatching { opus.encoderSetComplexity(VoipConfig.OPUS_COMPLEXITY) }
        setEncoderBitrate(opus, cfg.br)
        enableOpusControls(opus, cfg.br)
        session.opus = opus

        val acquiredAudioSession = ensureCallAudioSession(session)
        session.recorder?.let { runCatching { it.stop() } }
        session.recorder?.release()
        session.recorder = null
        session.player?.let { runCatching { it.stop() } }
        session.player?.release()
        session.player = null

        val recorder = createAudioRecorder(cfg) ?: run {
            if (acquiredAudioSession) {
                releaseCallAudioSession(session)
            }
            return false
        }
        val player = createAudioTrack(cfg) ?: run {
            recorder.release()
            if (acquiredAudioSession) {
                releaseCallAudioSession(session)
            }
            return false
        }

        session.recorder = recorder
        session.player = player
        updatePlayerOutputRoute(player, session.currentRoute)
        setPlayerGain(player)

        session.audioEffects = enableAudioEffects(recorder)
        runCatching { recorder.startRecording() }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord failed to start recording for ${session.sessionCode}/${session.callId}")
            runCatching { recorder.release() }
            runCatching { player.release() }
            session.recorder = null
            session.player = null
            if (acquiredAudioSession) releaseCallAudioSession(session)
            return false
        }
        runCatching { player.play() }
        if (player.playState != AudioTrack.PLAYSTATE_PLAYING) {
            Log.e(TAG, "AudioTrack failed to start playback for ${session.sessionCode}/${session.callId}")
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { player.release() }
            session.recorder = null
            session.player = null
            if (acquiredAudioSession) releaseCallAudioSession(session)
            return false
        }

        session.jitterBuffer = JitterBuffer(
            initialTargetDelayMs = VoipConfig.JITTER_DEFAULT_DELAY_MS,
            maxBufferedFrames = session.profile.jitterCapacity
        )
        session.seq.set(0)
        session.lastInboundSeq = -1
        session.lastPlayoutFrame = null
        session.netStats.apply {
            avgQueueDepth = 0.0
            playoutUnderruns = 0
            framesReceived = 0
            lostFrames = 0
            outOfOrderFrames = 0
            duplicateFrames = 0
            lateDrops = 0
            invalidPayloadFrames = 0
            invalidCrcFrames = 0
            concealedFrames = 0
        }
        if (session.connectedAt == null) {
            session.netStats.clearReceiverObservedAgeSamples()
            session.netStats.clearClockAdjustedAgeSamples()
            session.netStats.clearClockSyncSamples()
        }
        val connectedAt = resolveCallConnectedAt(session.connectedAt, System.currentTimeMillis())
        session.connectedAt = connectedAt
        markCallAnswered(session.sessionCode, session.callId, connectedAt)

        session.senderJob = startSenderJob(session, thread)
        session.playerJob = startPlayerJob(session)
        session.adaptationJob = startAdaptationJob(session, thread)
        session.syncJob = startClockSyncJob(session, thread)
        session.netStats.lastAdaptTs = SystemClock.elapsedRealtime()

        publishCallState(session)
        return true
    }

    private companion object {
        private const val TAG = "RfcommService"
    }
}
