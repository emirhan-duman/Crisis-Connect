package com.auralis.crisisconnect.service

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Shared radio policy for BLE scan/advertise/connect behavior.
 * The goal is to avoid keeping every component at LOW_LATENCY + HIGH_TX all the time.
 */
object BleRadioPolicy {

    data class Decision(
        val scanMode: Int,
        val advertiseMode: Int,
        val advertiseTxPower: Int,
        val connectionPriority: Int,
        val profile: Profile,
    )

    enum class Profile {
        LOW_POWER,
        BALANCED,
        PERFORMANCE,
    }

    fun resolve(
        context: Context,
        preferPerformance: Boolean,
        hasActiveTransfer: Boolean = false,
        connectedPeerCount: Int = 0
    ): Decision {
        val appContext = context.applicationContext
        val battery = batterySnapshot(appContext)
        val lowBattery = battery.levelPercent != null && battery.levelPercent <= LOW_BATTERY_THRESHOLD_PERCENT
        val veryLowBattery = battery.levelPercent != null && battery.levelPercent <= VERY_LOW_BATTERY_THRESHOLD_PERCENT
        val shouldBoost = preferPerformance || hasActiveTransfer || connectedPeerCount > 0

        val profile = when {
            (battery.isPowerSaveMode || veryLowBattery) && !battery.isCharging -> Profile.LOW_POWER
            shouldBoost || battery.isCharging -> Profile.PERFORMANCE
            lowBattery && !battery.isCharging -> Profile.LOW_POWER
            else -> Profile.BALANCED
        }

        return when (profile) {
            Profile.LOW_POWER -> Decision(
                scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER,
                profile = profile
            )

            Profile.BALANCED -> Decision(
                scanMode = ScanSettings.SCAN_MODE_BALANCED,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
                profile = profile
            )

            Profile.PERFORMANCE -> Decision(
                scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                profile = profile
            )
        }
    }

    private data class BatterySnapshot(
        val levelPercent: Int?,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean,
    )

    private fun batterySnapshot(context: Context): BatterySnapshot {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val levelPercent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val isPowerSaveMode = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
        return BatterySnapshot(
            levelPercent = levelPercent,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode
        )
    }

    private const val LOW_BATTERY_THRESHOLD_PERCENT = 25
    private const val VERY_LOW_BATTERY_THRESHOLD_PERCENT = 15
}
