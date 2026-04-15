package com.auralis.crisisconnect.service

import android.content.Intent
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow

internal class RfcommCallStateDelegate(
    private val callsState: MutableStateFlow<Map<String, CallUiState>>,
    private val sendBroadcastIntent: (Intent) -> Unit,
    private val actionCallStateChanged: String,
    private val extraSessionCodeKey: String,
    private val extraCallIdKey: String,
    private val extraCallMutedKey: String,
    private val extraCallSpeakerKey: String,
    private val extraCallFlagKey: String,
    private val handler: Handler,
    private val callSetupTimeoutMs: Long,
    private val onCallTimeout: (String, String) -> Unit,
    private val onStateInCall: (CallSession) -> Unit,
    private val onStateEnded: (CallSession) -> Unit
) {
    fun updateCallState(session: CallSession, state: CallState) {
        if (session.state.value != state) {
            session.state.value = state
        }
        if (state == CallState.InCall || state == CallState.Ended) {
            cancelCallTimeout(session)
        }
        publishCallState(session)
        when (state) {
            CallState.InCall -> onStateInCall(session)
            CallState.Ended -> onStateEnded(session)
            else -> Unit
        }
    }

    fun publishCallState(session: CallSession) {
        val updated = callsState.value.toMutableMap()
        updated[session.sessionCode] = CallUiState(
            sessionCode = session.sessionCode,
            callId = session.callId,
            state = session.state.value,
            isOutgoing = session.isOutgoing,
            encrypted = session.cfg.encrypted,
            sampleRate = session.cfg.sr,
            channels = session.cfg.ch,
            frameMs = session.cfg.frm,
            bitrate = session.cfg.br,
            startedAt = session.startedAt,
            connectedAt = session.connectedAt,
            muted = session.muted.value,
            speakerEnabled = session.speakerEnabled.value,
            currentRoute = session.currentRoute,
            availableRoutes = session.availableRoutes,
            profile = session.profile,
            jitterCapacity = session.profile.jitterCapacity
        )
        callsState.value = updated
        broadcastCallState(session)
    }

    fun clearCallState(sessionCode: String) {
        val updated = callsState.value.toMutableMap()
        if (updated.remove(sessionCode) != null) {
            callsState.value = updated
        }
    }

    fun broadcastCallState(session: CallSession) {
        val intent = Intent(actionCallStateChanged).apply {
            putExtra(extraSessionCodeKey, session.sessionCode)
            putExtra(extraCallIdKey, session.callId)
            putExtra(extraCallMutedKey, session.muted.value)
            putExtra(extraCallSpeakerKey, session.speakerEnabled.value)
        }
        sendBroadcastIntent(intent)
    }

    fun broadcastCallEvent(sessionCode: String, callId: String, flag: String) {
        val intent = Intent(actionCallStateChanged).apply {
            putExtra(extraSessionCodeKey, sessionCode)
            putExtra(extraCallIdKey, callId)
            putExtra(extraCallFlagKey, flag)
        }
        sendBroadcastIntent(intent)
    }

    fun scheduleCallTimeout(session: CallSession) {
        cancelCallTimeout(session)
        val runnable = Runnable {
            if (session.state.value != CallState.InCall && session.state.value != CallState.Ended) {
                onCallTimeout(session.sessionCode, session.callId)
            }
        }
        session.timeout = runnable
        handler.postDelayed(runnable, callSetupTimeoutMs)
    }

    fun cancelCallTimeout(session: CallSession) {
        val runnable = session.timeout ?: return
        handler.removeCallbacks(runnable)
        session.timeout = null
    }
}
