package com.auralis.crisisconnect.data

import android.content.Context
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val MAX_STORED_CALL_EVENTS = 200

private fun callEventDao(context: Context) = AppDatabase.getInstance(context).callEventDao()

fun observeCallEvents(context: Context, sessionCode: String): Flow<List<CallEvent>> =
    callEventDao(context).observeForSession(sessionCode).map { list ->
        list.map { it.toCallEvent() }
    }

fun observeLatestCallEvents(context: Context): Flow<Map<String, CallEvent>> =
    callEventDao(context).observeLatestEvents().map { list ->
        list.associateBy({ it.sessionCode }, { it.toCallEvent() })
    }

/**
 * [analyticsTransport] ("bluetooth" / "p2p_gatt") opts this event into the call_connected /
 * call_ended metrics; both Bluetooth transports emit exactly one event per call here, so this is
 * their single counting point. Events carry no identifiers — only transport, result and duration.
 */
suspend fun saveCallEvent(context: Context, event: CallEvent, analyticsTransport: String? = null) {
    if (analyticsTransport != null) {
        if (event.result == CallResult.ANSWERED) {
            Analytics.callConnected(analyticsTransport)
        }
        Analytics.callEnded(
            transport = analyticsTransport,
            durationSeconds = (event.durationMillis ?: 0L) / 1000L,
            result = event.result.name.lowercase()
        )
    }
    withContext(Dispatchers.IO) {
        val dao = callEventDao(context)
        dao.insert(event.toEntity())
        dao.trim(event.sessionCode, MAX_STORED_CALL_EVENTS)
    }
}
