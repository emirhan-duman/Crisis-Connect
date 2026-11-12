package com.auralis.crisisconnect.service

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class RfcommAutostartReceiverTest {
    @Test
    fun bluetoothNotificationTargetsAnExportedSystemSettingsActivity() {
        val privateActivity = settingsActivity("SettingsPrivate", exported = false)
        val publicActivity = settingsActivity("BluetoothSettings", exported = true)
        val action = bluetoothSettingsAction(privateActivity, publicActivity)
        val intent = shadowOf(requireNotNull(action)).savedIntent

        assertEquals(ComponentName("com.example.systemsettings", "BluetoothSettings"), intent.component)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, intent.action)
    }

    @Test
    fun bluetoothNotificationOmitsActionWhenSystemSettingsIsUnavailable() {
        assertNull(bluetoothSettingsAction(settingsActivity("SettingsPrivate", exported = false)))
    }

    private fun bluetoothSettingsAction(vararg handlers: ResolveInfo): PendingIntent? {
        val pm = mockk<PackageManager>()
        every {
            pm.queryIntentActivities(
                match { it.action == Settings.ACTION_BLUETOOTH_SETTINGS },
                PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
            )
        } returns handlers.toList()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageManager(): PackageManager = pm
        }
        val factory = RfcommAutostartReceiver::class.java.getDeclaredMethod(
            "bluetoothSettingsIntent", Context::class.java
        )
        factory.isAccessible = true
        return factory.invoke(RfcommAutostartReceiver(), context) as PendingIntent?
    }

    private fun settingsActivity(className: String, exported: Boolean): ResolveInfo =
        ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.systemsettings"
                name = className
                this.exported = exported
            }
        }
}
