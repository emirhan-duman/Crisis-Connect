package com.auralis.crisisconnect.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Small helpers for opting emergency traffic into carrier satellite data. */
object SatelliteNetworkStatus {
    fun isSatellite(context: Context): Boolean = capabilities(context)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) == true

    /** Satellite paths remain constrained even when a provider exposes an unconstrained plan. */
    fun isConstrained(context: Context): Boolean {
        val capabilities = capabilities(context) ?: return false
        val satellite = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
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

    private fun capabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)
    }
}
