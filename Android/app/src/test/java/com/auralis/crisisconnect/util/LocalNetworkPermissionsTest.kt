package com.auralis.crisisconnect.util

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkPermissionsTest {
    @Test
    fun `only Android 17 adds local network permission to nearby wifi flow`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), LocalNetworkPermissions.wifiPermissions(32))
        for (sdk in listOf(33, 36)) {
            assertEquals(listOf(Manifest.permission.NEARBY_WIFI_DEVICES), LocalNetworkPermissions.wifiPermissions(sdk))
        }
        assertEquals(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, LocalNetworkPermissions.ACCESS_LOCAL_NETWORK),
            LocalNetworkPermissions.wifiPermissions(37)
        )
    }
}
