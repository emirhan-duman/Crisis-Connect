package com.auralis.crisisconnect.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Wi-Fi discovery and TCP mesh permissions, separate from the Bluetooth service baseline. */
object LocalNetworkPermissions {
    const val ACCESS_LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun wifiPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdk >= 37) add(ACCESS_LOCAL_NETWORK)
    }

    fun hasWifiPermissions(context: Context): Boolean = wifiPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
