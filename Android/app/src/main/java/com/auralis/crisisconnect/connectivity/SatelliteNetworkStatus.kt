package com.auralis.crisisconnect.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ext.SdkExtensions

/** Small helpers for opting emergency traffic into carrier satellite data. */
object SatelliteNetworkStatus {
    fun isSatellite(context: Context): Boolean = capabilities(context)?.isSatelliteTransport() == true

    /** Satellite paths remain constrained even when a provider exposes an unconstrained plan. */
    fun isConstrained(context: Context): Boolean {
        val capabilities = capabilities(context) ?: return false
        val satellite = capabilities.isSatelliteTransport()
        val bandwidthCapabilitySupported = Build.VERSION.SDK_INT >= 36
        val bandwidthUnconstrained = bandwidthCapabilitySupported &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
        return ConstrainedMessagePolicy.isConstrained(
            satellite,
            bandwidthUnconstrained,
            bandwidthCapabilitySupported
        )
    }

    /** Usable for a best-effort emergency uplink, even if Android can't validate the route. */
    fun hasEmergencyInternet(context: Context): Boolean {
        val capabilities = capabilities(context) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            isConstrained(context)
    }

    private fun NetworkCapabilities.isSatelliteTransport(): Boolean {
        if (Build.VERSION.SDK_INT >= 35) return hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
        if (Build.VERSION.SDK_INT >= 34 && SdkExtensions.getExtensionVersion(34) >= 12) {
            return hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
        }
        return false
    }

    private fun capabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)
    }
}
