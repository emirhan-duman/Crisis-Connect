package com.auralis.crisisconnect.screens.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal enum class BluetoothCapability {
    LOW_ENERGY, CODED_PHY, PHY_2M, EXTENDED_ADVERTISING, PERIODIC_ADVERTISING,
    MULTIPLE_ADVERTISEMENTS, OFFLOADED_FILTERING, OFFLOADED_BATCHING
}

internal enum class BluetoothCapabilityAvailability {
    LOADING, READY, BLUETOOTH_OFF, PERMISSION_REQUIRED, NO_ADAPTER, UNAVAILABLE
}

internal data class BluetoothCapabilities(
    val availability: BluetoothCapabilityAvailability = BluetoothCapabilityAvailability.LOADING,
    val features: Map<BluetoothCapability, Boolean?> = emptyMap(),
    val maximumAdvertisingBytes: Int? = null,
) {
    operator fun get(capability: BluetoothCapability): Boolean? = features[capability]
}

/** Reads the public Android API only. It never scans, advertises, or changes the radio state. */
@SuppressLint("MissingPermission")
internal fun readBluetoothCapabilities(context: Context): BluetoothCapabilities {
    val lowEnergy = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val baseFeatures = mapOf<BluetoothCapability, Boolean?>(BluetoothCapability.LOW_ENERGY to lowEnergy)
    fun unavailable(availability: BluetoothCapabilityAvailability) = BluetoothCapabilities(
        availability = availability,
        features = baseFeatures,
    )
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return unavailable(BluetoothCapabilityAvailability.NO_ADAPTER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return unavailable(BluetoothCapabilityAvailability.PERMISSION_REQUIRED)
        }
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            return unavailable(BluetoothCapabilityAvailability.BLUETOOTH_OFF)
        }

        // A failed query is unknown, never a negative capability result.
        fun query(block: () -> Boolean): Boolean? = runCatching(block).getOrNull()
        val features = baseFeatures.toMutableMap()
        features[BluetoothCapability.MULTIPLE_ADVERTISEMENTS] = query { adapter.isMultipleAdvertisementSupported }
        features[BluetoothCapability.OFFLOADED_FILTERING] = query { adapter.isOffloadedFilteringSupported }
        features[BluetoothCapability.OFFLOADED_BATCHING] = query { adapter.isOffloadedScanBatchingSupported }
        var maximumBytes: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            features[BluetoothCapability.CODED_PHY] = query { adapter.isLeCodedPhySupported }
            features[BluetoothCapability.PHY_2M] = query { adapter.isLe2MPhySupported }
            features[BluetoothCapability.EXTENDED_ADVERTISING] = query { adapter.isLeExtendedAdvertisingSupported }
            features[BluetoothCapability.PERIODIC_ADVERTISING] = query { adapter.isLePeriodicAdvertisingSupported }
            if (features[BluetoothCapability.EXTENDED_ADVERTISING] == true) {
                maximumBytes = runCatching { adapter.leMaximumAdvertisingDataLength }
                    .getOrNull()?.takeIf { it > 0 }
            }
        }
        // The adapter can turn off while the binder queries above are running.
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            unavailable(BluetoothCapabilityAvailability.BLUETOOTH_OFF)
        } else {
            BluetoothCapabilities(BluetoothCapabilityAvailability.READY, features, maximumBytes)
        }
    } catch (_: SecurityException) {
        unavailable(BluetoothCapabilityAvailability.PERMISSION_REQUIRED)
    } catch (_: RuntimeException) {
        unavailable(BluetoothCapabilityAvailability.UNAVAILABLE)
    }
}
