package com.auralis.crisisconnect.watch

import android.content.Context
import android.content.SharedPreferences

data class WatchBatteryInfo(
    val percent: Int,
    val isCharging: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class WatchBatteryStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("watch_battery_store", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PERCENT = "battery_percent"
        private const val KEY_CHARGING = "battery_charging"
        private const val KEY_TIMESTAMP = "battery_timestamp"
        private const val EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    fun saveBattery(percent: Int, isCharging: Boolean = false) {
        if (percent !in 0..100) return
        prefs.edit()
            .putInt(KEY_PERCENT, percent)
            .putBoolean(KEY_CHARGING, isCharging)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getBattery(): WatchBatteryInfo? {
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (timestamp == 0L || System.currentTimeMillis() - timestamp > EXPIRATION_MS) {
            return null
        }
        val percent = prefs.getInt(KEY_PERCENT, -1)
        if (percent !in 0..100) return null
        val charging = prefs.getBoolean(KEY_CHARGING, false)
        return WatchBatteryInfo(percent, charging, timestamp)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
