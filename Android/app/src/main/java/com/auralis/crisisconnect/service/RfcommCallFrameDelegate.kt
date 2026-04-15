package com.auralis.crisisconnect.service

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.service.voice.JitterBuffer
import com.auralis.crisisconnect.service.voice.VoipConfig
import com.auralis.opuscodec.Opus
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal class RfcommCallFrameDelegate(
    private val appContext: Context,
    private val connectedThreads: ConcurrentHashMap<String, RfcommForegroundService.ConnectedThread>,
    private val callSessions: ConcurrentHashMap<String, CallSession>,
    private val teardownCall: (CallSession, String, Boolean, RfcommForegroundService.CallResult?) -> Unit,
    private val sanitizeCfg: (Int, Int, Int, Int, Boolean, Boolean) -> Pair<Profile, AudioCfg>?,
    private val trackIncomingCallStart: (String, String, Long) -> Unit,
    private val sendRing: (RfcommForegroundService.ConnectedThread, String) -> Boolean,
    private val scheduleCallTimeout: (CallSession) -> Unit,
    private val updateCallState: (CallSession, CallState) -> Unit,
    private val showIncomingCallNotification: (CallSession) -> Unit,
    private val stopRingtone: (CallSession) -> Unit,
    private val cancelCallNotification: (String) -> Unit,
    private val prepareAudioPipelines: (CallSession, RfcommForegroundService.ConnectedThread) -> Boolean,
    private val endVoipCall: (String, String, String) -> Unit,
    private val clearCallState: (String) -> Unit,
    private val sendCallCfgAck: (RfcommForegroundService.ConnectedThread, String, Boolean) -> Boolean,
    private val sendClockSyncResponse: (
        RfcommForegroundService.ConnectedThread,
        String,
        String,
        Long,
        Long,
        Long
    ) -> Boolean,
    private val applyAudioConfig: (CallSession, RfcommForegroundService.ConnectedThread, Profile, AudioCfg) -> Boolean,
    private val enqueueSilenceFrames: (CallSession, Int, Int) -> Unit,
    private val decodeIncomingCallPayload: (CallSession, String, String?, Int, Long) -> ByteArray?,
    private val opusFrameSize: (Int) -> Int?,
    private val processIncomingOpusFrame: (CallSession, Opus, Int, ByteArray, Int) -> Unit
) {
    fun handleCallFrame(sessionCode: String, json: JSONObject): Boolean {
        val type = json.optString("type")?.takeIf { it.isNotBlank() } ?: return true
        val callId = json.optString("id")?.takeIf { it.isNotBlank() }
        val thread = connectedThreads[sessionCode]

        when (type) {
            "call:offer" -> {
                if (callId == null) {
                    return true
                }
                callSessions.remove(sessionCode)?.let { previous ->
                    teardownCall(previous, "hangup", false, null)
                }
                val sampleRate = json.optInt("sampleRate", VoipConfig.SAMPLE_RATE_HZ)
                val frameMs = json.optInt("frameMs", VoipConfig.FRAME_DURATION_MS)
                val bitrate = json.optInt("bitrate", VoipConfig.OPUS_BITRATE_BPS)
                val framesPerPacket = json.optInt("framesPerPacket", VoipConfig.FRAMES_PER_PACKET)
                val contact = getContact(appContext, sessionCode)
                val aesKeyBytes = contact?.aesKey
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                val remoteDisplayName = contact?.name
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank() && !BlePeerIdentityUtils.looksLikeBleIdentifier(it, sessionCode)
                    }
                val encryptedRequested = json.optBoolean("encrypted", false)
                val sanitized = sanitizeCfg(
                    sampleRate,
                    frameMs,
                    framesPerPacket,
                    bitrate,
                    encryptedRequested,
                    aesKeyBytes != null
                ) ?: return true
                val (profile, cfg) = sanitized
                val session = CallSession(
                    sessionCode = sessionCode,
                    callId = callId,
                    aesKey = aesKeyBytes,
                    isOutgoing = false
                ).also {
                    it.profile = profile
                    it.cfg = cfg
                    it.remoteDisplayName = remoteDisplayName
                    it.jitterBuffer = JitterBuffer(
                        initialTargetDelayMs = VoipConfig.JITTER_DEFAULT_DELAY_MS,
                        maxBufferedFrames = profile.jitterCapacity
                    )
                }
                callSessions[sessionCode] = session
                trackIncomingCallStart(sessionCode, callId, session.startedAt)
                thread?.let { sendRing(it, callId) }
                scheduleCallTimeout(session)
                updateCallState(session, CallState.Ringing)
                showIncomingCallNotification(session)
            }

            "call:ring" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId) {
                    return true
                }
                updateCallState(session, CallState.Ringing)
            }

            "call:accept" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || thread == null) {
                    return true
                }
                stopRingtone(session)
                cancelCallNotification(sessionCode)
                val ready = prepareAudioPipelines(session, thread)
                if (ready) {
                    updateCallState(session, CallState.InCall)
                } else {
                    endVoipCall(sessionCode, callId, "error")
                }
            }

            "call:reject" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions.remove(sessionCode) ?: return true
                Log.i(TAG, "Remote rejected call $sessionCode/$callId reason=${json.optString("reason", "declined")}")
                stopRingtone(session)
                cancelCallNotification(sessionCode)
                teardownCall(
                    session,
                    json.optString("reason", "declined"),
                    false,
                    RfcommForegroundService.CallResult.REJECTED
                )
                clearCallState(sessionCode)
            }

            "call:end" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions.remove(sessionCode) ?: return true
                Log.i(TAG, "Remote ended call $sessionCode/$callId reason=${json.optString("reason", "remote")}")
                stopRingtone(session)
                cancelCallNotification(sessionCode)
                teardownCall(session, json.optString("reason", "remote"), false, null)
                clearCallState(sessionCode)
            }

            "call:cfg" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || thread == null) {
                    thread?.let { sendCallCfgAck(it, callId, false) }
                    return true
                }
                val sr = json.optInt("sr", session.cfg.sr)
                val frm = json.optInt("frm", session.cfg.frm)
                val fps = json.optInt("fps", session.cfg.fps)
                val br = json.optInt("br", session.cfg.br)
                val enc = if (json.has("enc")) json.optBoolean("enc") else session.cfg.encrypted
                val sanitized = sanitizeCfg(sr, frm, fps, br, enc, session.aesKey != null)
                val previousProfile = session.profile
                val previousCfg = session.cfg
                val ok = if (sanitized != null) {
                    val (profile, cfg) = sanitized
                    val ready = applyAudioConfig(session, thread, profile, cfg)
                    if (ready) {
                        session.pendingProfile = null
                        session.pendingCfg = null
                        session.awaitingAck = false
                        session.netStats.lastAdaptTs = SystemClock.elapsedRealtime()
                    } else {
                        session.profile = previousProfile
                        session.cfg = previousCfg
                        prepareAudioPipelines(session, thread)
                    }
                    ready
                } else {
                    false
                }
                sendCallCfgAck(thread, callId, ok)
            }

            "call:cfg_ack" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || thread == null) {
                    return true
                }
                val ok = json.optBoolean("ok", false)
                val pendingProfile = session.pendingProfile
                val pendingCfg = session.pendingCfg
                session.pendingProfile = null
                session.pendingCfg = null
                session.awaitingAck = false
                if (ok && pendingProfile != null && pendingCfg != null) {
                    val previousProfile = session.profile
                    val previousCfg = session.cfg
                    val ready = applyAudioConfig(session, thread, pendingProfile, pendingCfg)
                    if (ready) {
                        session.netStats.lastAdaptTs = SystemClock.elapsedRealtime()
                    } else {
                        session.profile = previousProfile
                        session.cfg = previousCfg
                        prepareAudioPipelines(session, thread)
                    }
                } else {
                    session.netStats.lastAdaptTs = SystemClock.elapsedRealtime()
                }
            }

            "call:sync_req" -> {
                if (!BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED) {
                    return true
                }
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || thread == null || session.state.value == CallState.Ended) {
                    return true
                }
                val probeId = json.optString("probeId")?.takeIf { it.isNotBlank() } ?: return true
                val requesterMonotonicSendMs = json.optLong("monoSendMs", 0L)
                val responderMonotonicReceiveMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "Clock sync request received $sessionCode/$callId probeId=$probeId " +
                        "reqMonoSendMs=$requesterMonotonicSendMs recvMonoMs=$responderMonotonicReceiveMs"
                )
                sendClockSyncResponse(
                    thread,
                    callId,
                    probeId,
                    requesterMonotonicSendMs,
                    responderMonotonicReceiveMs,
                    SystemClock.elapsedRealtime()
                )
            }

            "call:sync_resp" -> {
                if (!BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED) {
                    return true
                }
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || session.state.value == CallState.Ended) {
                    return true
                }
                val probeId = json.optString("probeId")?.takeIf { it.isNotBlank() } ?: return true
                val requestSentAtMs = session.pendingClockSyncProbes.remove(probeId)
                    ?: json.optLong("reqMonoSendMs", 0L)
                val remoteReceivedAtMs = json.optLong("reqRecvMonoMs", 0L)
                val remoteSentAtMs = json.optLong("respSendMonoMs", 0L)
                val responseReceivedAtMs = SystemClock.elapsedRealtime()
                val observation = computeClockSyncObservation(
                    requestSentAtMs = requestSentAtMs,
                    remoteReceivedAtMs = remoteReceivedAtMs,
                    remoteSentAtMs = remoteSentAtMs,
                    responseReceivedAtMs = responseReceivedAtMs
                )
                if (observation != null) {
                    if (session.netStats.recordClockSyncObservation(observation)) {
                        Log.i(
                            TAG,
                            "Clock sync response applied $sessionCode/$callId probeId=$probeId " +
                                "offsetMs=${"%.1f".format(java.util.Locale.US, observation.remoteClockOffsetMs)} " +
                                "rttMs=${"%.1f".format(java.util.Locale.US, observation.rttMs)}"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Clock sync response discarded $sessionCode/$callId probeId=$probeId " +
                                "offsetMs=${"%.1f".format(java.util.Locale.US, observation.remoteClockOffsetMs)} " +
                                "rttMs=${"%.1f".format(java.util.Locale.US, observation.rttMs)}"
                        )
                    }
                } else {
                    session.netStats.clockSyncDiscardedSamples += 1
                    Log.w(
                        TAG,
                        "Clock sync response discarded $sessionCode/$callId probeId=$probeId " +
                            "reqSent=$requestSentAtMs remoteRecv=$remoteReceivedAtMs remoteSend=$remoteSentAtMs localRecv=$responseReceivedAtMs"
                    )
                }
            }

            "call:audio" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || session.state.value == CallState.Ended) {
                    return true
                }
                runCatching {
                    val seq = json.optInt("seq", -1)
                    val receivedAtElapsedMs = SystemClock.elapsedRealtime()
                    val senderMonotonicTs = json.optLong("monoTs", 0L)
                    val latencyDiagnosticsEnabled = BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED
                    if (json.optBoolean("silent", false)) {
                        if (latencyDiagnosticsEnabled) {
                            session.netStats.recordReceiverObservedAgeSample(json.optLong("timestamp", 0L))
                            session.netStats.recordClockAdjustedAgeSample(senderMonotonicTs, receivedAtElapsedMs)
                        }
                        enqueueSilenceFrames(session, seq, 1)
                        return@runCatching true
                    }
                    val codec = json.optString("codec")?.takeIf { it.isNotBlank() }
                    if (codec != null && !codec.equals("opus", ignoreCase = true)) {
                        session.netStats.invalidPayloadFrames += 1
                        return@runCatching true
                    }
                    val payloadLen = when {
                        json.has("payload_len") -> json.optInt("payload_len", -1)
                        !session.cfg.encrypted && json.has("sz") -> json.optInt("sz", -1)
                        else -> -1
                    }
                    val payloadB64 = json.optString("b64")?.takeIf { it.isNotBlank() }
                    if (payloadB64 == null) {
                        if (payloadLen == 0) {
                            enqueueSilenceFrames(session, seq, 1)
                        } else {
                            session.netStats.invalidPayloadFrames += 1
                        }
                        return@runCatching true
                    }
                    if (latencyDiagnosticsEnabled) {
                        session.netStats.recordReceiverObservedAgeSample(json.optLong("timestamp", 0L))
                        session.netStats.recordClockAdjustedAgeSample(senderMonotonicTs, receivedAtElapsedMs)
                    }
                    val crc = if (json.has("crc")) json.optLong("crc", -1L) else -1L
                    val ivB64 = json.optString("ivB64")?.takeIf { it.isNotBlank() }
                    val encodedBytes = decodeIncomingCallPayload(session, payloadB64, ivB64, payloadLen, crc)
                        ?: return@runCatching true
                    val opus = session.opus ?: return@runCatching true
                    val frameConst = session.frameSizeConst ?: opusFrameSize(session.cfg.frameSamples)
                    if (frameConst == null) {
                        return@runCatching true
                    }
                    processIncomingOpusFrame(session, opus, frameConst, encodedBytes, seq)
                }.onFailure { Log.e(TAG, "Failed to handle call audio frame", it) }
            }

            "call:audiox" -> {
                if (callId == null) {
                    return true
                }
                val session = callSessions[sessionCode] ?: return true
                if (session.callId != callId || session.state.value == CallState.Ended) {
                    return true
                }
                runCatching {
                    val n = json.optInt("n", 0)
                    if (n <= 0) {
                        return true
                    }
                    val firstSeq = json.optInt("seq", -1)
                    val receivedAtElapsedMs = SystemClock.elapsedRealtime()
                    val senderMonotonicTs = json.optLong("monoTs", 0L)
                    val latencyDiagnosticsEnabled = BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED
                    if (json.optBoolean("silent", false)) {
                        if (latencyDiagnosticsEnabled) {
                            session.netStats.recordReceiverObservedAgeSample(json.optLong("timestamp", 0L))
                            session.netStats.recordClockAdjustedAgeSample(senderMonotonicTs, receivedAtElapsedMs)
                        }
                        enqueueSilenceFrames(session, firstSeq, n)
                        return@runCatching true
                    }
                    val codec = json.optString("codec")?.takeIf { it.isNotBlank() }
                    if (codec != null && !codec.equals("opus", ignoreCase = true)) {
                        session.netStats.invalidPayloadFrames += 1
                        return@runCatching true
                    }
                    if (latencyDiagnosticsEnabled) {
                        session.netStats.recordReceiverObservedAgeSample(json.optLong("timestamp", 0L))
                        session.netStats.recordClockAdjustedAgeSample(senderMonotonicTs, receivedAtElapsedMs)
                    }
                    val payloadArray = json.optJSONArray("b64") ?: return true
                    val ivArray = if (session.cfg.encrypted) json.optJSONArray("iv") else null
                    val payloadLenArray = when {
                        json.has("payload_len") -> json.optJSONArray("payload_len")
                        !session.cfg.encrypted && json.has("sz") -> json.optJSONArray("sz")
                        else -> null
                    }
                    val crcArray = json.optJSONArray("crc")
                    val opus = session.opus ?: return@runCatching true
                    val frameConst = session.frameSizeConst ?: opusFrameSize(session.cfg.frameSamples)
                    if (frameConst == null) {
                        return@runCatching true
                    }
                    val limit = minOf(n, payloadArray.length())
                    for (i in 0 until limit) {
                        val payloadLen = payloadLenArray?.optInt(i, -1) ?: -1
                        val payloadB64 = payloadArray.optString(i)?.takeIf { it.isNotBlank() }
                        val seq = if (firstSeq > 0) firstSeq + i else -1
                        if (payloadB64 == null) {
                            if (payloadLen == 0) {
                                enqueueSilenceFrames(session, seq, 1)
                            } else {
                                session.netStats.invalidPayloadFrames += 1
                            }
                            continue
                        }
                        val crc = crcArray?.optLong(i, -1L) ?: -1L
                        val ivB64 = ivArray?.optString(i)?.takeIf { it.isNotBlank() }
                        val encodedBytes = decodeIncomingCallPayload(session, payloadB64, ivB64, payloadLen, crc)
                            ?: continue
                        processIncomingOpusFrame(session, opus, frameConst, encodedBytes, seq)
                    }
                }.onFailure { Log.e(TAG, "Failed to handle batched call audio frame", it) }
            }

            else -> Unit
        }
        return true
    }

    private companion object {
        private const val TAG = "RfcommService"
    }
}
