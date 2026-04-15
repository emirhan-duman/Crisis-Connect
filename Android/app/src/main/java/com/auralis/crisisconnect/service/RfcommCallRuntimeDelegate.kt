package com.auralis.crisisconnect.service

import java.util.concurrent.ConcurrentHashMap

internal class RfcommCallRuntimeDelegate(
    private val callRuntimes: ConcurrentHashMap<String, CallRuntime>,
    private val emitCallEvent: (RfcommForegroundService.CallEvent) -> Unit
) {
    fun trackCallStart(
        sessionCode: String,
        callId: String,
        direction: RfcommForegroundService.CallDirection,
        startedAt: Long = System.currentTimeMillis()
    ) {
        callRuntimes[callRuntimeKey(sessionCode, callId)] = CallRuntime(
            callId = callId,
            sessionCode = sessionCode,
            startedAt = startedAt,
            direction = direction
        )
    }

    fun markCallAnswered(sessionCode: String, callId: String, connectedAt: Long) {
        callRuntimes[callRuntimeKey(sessionCode, callId)]?.let { runtime ->
            if (runtime.answeredAt == null) {
                runtime.answeredAt = connectedAt
            }
        }
    }

    fun finalizeCallRuntime(
        sessionCode: String,
        callId: String,
        resultOverride: RfcommForegroundService.CallResult? = null
    ): CallNotificationSummary? {
        val key = callRuntimeKey(sessionCode, callId)
        val runtime = callRuntimes.remove(key) ?: return null
        if (runtime.endedAt != null) {
            return null
        }
        val now = System.currentTimeMillis()
        runtime.endedAt = now
        val result = resultOverride ?: when {
            runtime.answeredAt != null -> RfcommForegroundService.CallResult.ANSWERED
            runtime.direction == RfcommForegroundService.CallDirection.INCOMING -> RfcommForegroundService.CallResult.MISSED
            else -> RfcommForegroundService.CallResult.CANCELED
        }
        runtime.result = result
        val duration = if (result == RfcommForegroundService.CallResult.ANSWERED && runtime.answeredAt != null) {
            (now - runtime.answeredAt!!).coerceAtLeast(0L)
        } else {
            null
        }
        val timestamp = if (result == RfcommForegroundService.CallResult.MISSED) runtime.startedAt else now
        emitCallEvent(
            RfcommForegroundService.CallEvent(
                id = runtime.callId,
                sessionCode = runtime.sessionCode,
                timestampMillis = timestamp,
                direction = runtime.direction,
                result = result,
                durationMillis = duration
            )
        )
        return CallNotificationSummary(
            direction = runtime.direction,
            result = result,
            durationMillis = duration
        )
    }

    private fun callRuntimeKey(sessionCode: String, callId: String): String = "$sessionCode|$callId"
}
