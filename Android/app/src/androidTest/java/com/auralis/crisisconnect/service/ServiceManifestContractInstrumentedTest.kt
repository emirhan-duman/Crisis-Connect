package com.auralis.crisisconnect.service

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceManifestContractInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun crisisLinkService_isDeclaredEnabledAndNotExported() {
        val serviceInfo = readServiceInfo(ComponentName(context, CrisisLinkForegroundService::class.java))

        assertTrue(serviceInfo.enabled)
        assertFalse(serviceInfo.exported)
    }

    @Test
    fun gattMeshService_isDeclaredEnabledAndNotExported() {
        val serviceInfo = readServiceInfo(ComponentName(context, GattMeshForegroundService::class.java))

        assertTrue(serviceInfo.enabled)
        assertFalse(serviceInfo.exported)
    }

    @Test
    fun explicitServiceIntents_areResolvable() {
        val crisisIntent = Intent(context, CrisisLinkForegroundService::class.java)
        val gattIntent = Intent(context, GattMeshForegroundService::class.java)

        val crisisResolve = context.packageManager.resolveService(crisisIntent, 0)
        val gattResolve = context.packageManager.resolveService(gattIntent, 0)

        assertNotNull(crisisResolve)
        assertNotNull(gattResolve)
    }

    @Test
    fun foregroundServiceTypes_matchExpectedFlags_onApi29AndAbove() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        val crisisInfo = readServiceInfo(ComponentName(context, CrisisLinkForegroundService::class.java))
        val gattInfo = readServiceInfo(ComponentName(context, GattMeshForegroundService::class.java))

        val crisisType = crisisInfo.foregroundServiceType
        val gattType = gattInfo.foregroundServiceType
        assertTrue(
            crisisType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        )
        assertTrue(
            crisisType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
        )
        assertTrue(
            gattType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0
        )
    }

    @Suppress("DEPRECATION")
    private fun readServiceInfo(componentName: ComponentName): android.content.pm.ServiceInfo {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(componentName, PackageManager.ComponentInfoFlags.of(0))
        } else {
            packageManager.getServiceInfo(componentName, 0)
        }
    }
}
