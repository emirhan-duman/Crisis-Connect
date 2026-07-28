package com.auralis.crisisconnect.service.gattmesh

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny in-process diagnostic ring buffer for the GATT mesh runtime.
 *
 * Retail Samsung devices suppress the app's own `android.util.Log` from logcat (even over adb), which
 * made remote debugging of the authority-mesh connect churn impossible. This object keeps the last
 * [MAX_EVENTS] timestamped events in memory and exposes them as a [StateFlow] so a debug panel can
 * render them on-screen — bypassing logcat entirely. It is process-local (the rescue feature + the
 * mesh service run in the main process), cheap, and only surfaced in debug builds.
 *
 * Public (not internal) so the debug panel in the feature_rescue module can read [events].
 */
object MeshDiagnostics {

    private const val MAX_EVENTS = 80
    private const val TAG = "MeshDiag"

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun log(profileId: String, message: String) {
        val line = "${timeFormat.format(Date())} [$profileId] $message"
        synchronized(lock) {
            _events.value = (_events.value + line).takeLast(MAX_EVENTS)
        }
        Log.d(TAG, line)
    }

    fun clear() {
        synchronized(lock) { _events.value = emptyList() }
    }

    /**
     * A compact "who called me" hint: the app's own frames from the current call stack, newest first,
     * so a diagnostic line can name exactly which path triggered (e.g. which caller stopped a running
     * runtime). Filters out framework/coroutine frames to stay readable.
     */
    fun callerHint(maxFrames: Int = 5): String {
        return Throwable().stackTrace
            .asSequence()
            .filter { it.className.startsWith("com.auralis.crisisconnect") }
            .filterNot { it.methodName == "callerHint" }
            .take(maxFrames)
            .joinToString(" <- ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
            .ifEmpty { "?" }
    }
}
