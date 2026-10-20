package com.auralis.crisisconnect.connectivity

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Local-only counters. UID bytes include network-layer traffic, but NOT Play Services' push socket. */
object TrafficMeasurements {
    private var context: Context? = null
    private var mode = "normal"
    private var started = 0L
    private var rx = -1L
    private var tx = -1L
    private var requests = 0L
    private var failures = 0L
    private var jsonBytes = 0L
    @Synchronized fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        reset()
    }
    private fun reset() {
        started = SystemClock.elapsedRealtime()
        rx = TrafficStats.getUidRxBytes(Process.myUid()); tx = TrafficStats.getUidTxBytes(Process.myUid())
        requests = 0; failures = 0; jsonBytes = 0
    }
    @Synchronized fun request(succeeded: Boolean, responseJsonBytes: Long = 0) {
        requests++; if (!succeeded) failures++
        jsonBytes += responseJsonBytes.coerceAtLeast(0)
    }
    @Synchronized fun transition(next: String) {
        if (next == mode) return
        checkpoint(); mode = next; reset()
    }
    @Synchronized fun checkpoint() {
        val context = context ?: return
        val prefs = context.getSharedPreferences("traffic_measurements", Context.MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("windows", "[]")) }.getOrDefault(JSONArray())
        val currentRx = TrafficStats.getUidRxBytes(Process.myUid())
        val currentTx = TrafficStats.getUidTxBytes(Process.myUid())
        val item = JSONObject().put("mode", mode).put("recordedAtMs", System.currentTimeMillis())
            .put("durationMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0))
            .put("requests", requests).put("failures", failures).put("responseJsonBytes", jsonBytes)
            .put("uidRxBytes", if (rx >= 0 && currentRx >= rx) currentRx - rx else JSONObject.NULL)
            .put("uidTxBytes", if (tx >= 0 && currentTx >= tx) currentTx - tx else JSONObject.NULL)
        val bounded = JSONArray()
        for (i in maxOf(0, history.length() - 47) until history.length()) bounded.put(history.get(i))
        bounded.put(item)
        prefs.edit().putString("windows", bounded.toString()).apply()
        reset()
    }
}
