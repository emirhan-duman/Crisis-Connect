package com.auralis.crisisconnect.muster

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MusterUrgentAlertsTest {
    private lateinit var context: Context
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.getSharedPreferences("muster_urgent_v1", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun soundRequiresLiveUrgencyUserOptInPermissionAndSilentMode() {
        assertTrue(MusterUrgentAlerts.shouldSound(true, false, true, true, true))
        assertFalse(MusterUrgentAlerts.shouldSound(false, false, true, true, true))
        assertFalse(MusterUrgentAlerts.shouldSound(true, true, true, true, true))
        assertFalse(MusterUrgentAlerts.shouldSound(true, false, false, true, true))
        assertFalse(MusterUrgentAlerts.shouldSound(true, false, true, false, true))
        assertFalse(MusterUrgentAlerts.shouldSound(true, false, true, true, false))
    }
    @Test fun preferencesAreAccountScopedAndDurationIsBounded() {
        assertFalse(MusterUrgentAlerts.enabled(context, "a"))
        MusterUrgentAlerts.setEnabled(context, "a", true)
        assertTrue(MusterUrgentAlerts.enabled(context, "a")); assertFalse(MusterUrgentAlerts.enabled(context, "b"))
        MusterUrgentAlerts.setDuration(context, "a", 20)
        assertEquals(20, MusterUrgentAlerts.duration(context, "a")); assertEquals(10, MusterUrgentAlerts.duration(context, "b"))
        assertThrows(IllegalArgumentException::class.java) { MusterUrgentAlerts.setDuration(context, "a", 120) }
    }
    @Test fun duplicateWakesCannotRepeatedlyAlertButRemindersAndOtherAccountsCan() {
        assertTrue(MusterUrgentAlerts.firstWake(context, "a", "panel", "session", 0))
        assertFalse(MusterUrgentAlerts.firstWake(context, "a", "panel", "session", 0))
        assertTrue(MusterUrgentAlerts.firstWake(context, "a", "panel", "session", 1))
        assertTrue(MusterUrgentAlerts.firstWake(context, "b", "panel", "session", 0))
    }
    @Test fun urgentAndReminderWakesExpireWhileOrdinaryLegacyPushesRemainCompatible() {
        assertFalse(validMusterAlertExpiry("urgent", 0, null, 1000))
        assertFalse(validMusterAlertExpiry("urgent", 0, 999, 1000))
        assertFalse(validMusterAlertExpiry("standard", 1, 1000, 1000))
        assertTrue(validMusterAlertExpiry("urgent", 0, 1001, 1000))
        assertTrue(validMusterAlertExpiry("standard", 0, null, 1000))
    }
    @Test fun priorityAndTotalSilenceRemainRespected() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertFalse(MusterUrgentAlerts.policyAllows(context))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertFalse(MusterUrgentAlerts.policyAllows(context))
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        assertTrue(MusterUrgentAlerts.policyAllows(context))
    }
}
