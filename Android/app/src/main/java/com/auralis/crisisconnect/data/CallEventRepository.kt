package com.auralis.crisisconnect.data

import android.content.Context
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
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

suspend fun saveCallEvent(context: Context, event: CallEvent) {
    withContext(Dispatchers.IO) {
        val dao = callEventDao(context)
        dao.insert(event.toEntity())
        dao.trim(event.sessionCode, MAX_STORED_CALL_EVENTS)
    }
}
