package com.auralis.crisisconnect.muster

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.auralis.crisisconnect.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MusterNotificationsTest {
    private lateinit var context: Context
    private val panel = "example-factory"
    private val session = "0123456789abcdef0123456789abcdef"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("language_sync_prefs", Context.MODE_PRIVATE).edit().putString("language", "en").putBoolean("explicit_choice", true).commit()
        context.getSharedPreferences("muster_presentation_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun alertsUseTheInAppLanguageInsteadOfTheDeviceLanguage() {
        context.getSharedPreferences("language_sync_prefs", Context.MODE_PRIVATE).edit().putString("language", "tr").putBoolean("explicit_choice", true).commit()
        val notification = MusterNotifications.buildNotification(context, "alice", panel, session, true)
        assertEquals("Personel yoklaması", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(musterLocalizedContext(context).getString(R.string.muster_safe), notification.actions.first().title)
    }

    @Test fun configuredChecksOpenTheirFrozenFormWithoutInventingThreeLegacyAnswers() {
        val notification = MusterNotifications.buildNotification(context, "alice", panel, session, true, legacyActions = false)
        assertTrue(notification.actions.isNullOrEmpty())
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.fullScreenIntent)
        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
    }

    @Test fun soundAndVibrationRemainUnderTheUsersChannelSettings() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = NotificationChannel("personnel-muster", "My alerts", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) }
        manager.createNotificationChannel(existing)
        MusterNotifications.buildNotification(context, "alice", panel, session, true)
        val channel = manager.getNotificationChannel("personnel-muster")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.shouldVibrate())
        assertNull(channel.sound)
        assertFalse(MusterPresentation.highImportance(context))
    }

    @Test fun optingOutKeepsTheWhiteCcIconAndThreeAnswersWithoutFullScreen() {
        MusterPresentation.setEnabled(context, "alice", false)
        val notification = MusterNotifications.buildNotification(context, "alice", panel, session, true)
        assertNull(notification.fullScreenIntent)
        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        assertEquals(3, notification.actions.size)
        assertEquals(Notification.CATEGORY_EVENT, notification.category)
    }

    @Test fun fullScreenOpensTheSpecificDrillOnlyWithUserChoiceAndSystemPermission() {
        MusterPresentation.setEnabled(context, "alice", true)
        val notification = MusterNotifications.buildNotification(context, "alice", panel, session, true)
        assertNotNull(notification.fullScreenIntent)
        val intent = shadowOf(notification.fullScreenIntent).savedIntent
        assertEquals(MusterActivity::class.java.name, intent.component?.className)
        assertEquals(panel, intent.getStringExtra("panelId"))
        assertEquals(session, intent.getStringExtra("sessionId"))
    }

    @Test fun deniedSystemPermissionFallsBackToTheSameActionableNotification() {
        MusterPresentation.setEnabled(context, "alice", true)
        val notification = MusterNotifications.buildNotification(context, "alice", panel, session, false)
        assertNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
        assertEquals(3, notification.actions.size)
    }

    @Test fun fullScreenDefaultsOnWithoutBypassingTheSystemPermission() {
        assertTrue(MusterPresentation.enabled(context, "alice"))
        assertNotNull(MusterNotifications.buildNotification(context, "alice", panel, session, true).fullScreenIntent)
        assertNull(MusterNotifications.buildNotification(context, "alice", panel, session, false).fullScreenIntent)
    }

    @Test fun anExplicitOptOutIsPreservedAndDoesNotChangeAnotherAccountsPreference() {
        MusterPresentation.setEnabled(context, "alice", false)
        assertTrue(MusterPresentation.enabled(context, "bob"))
        MusterPresentation.setEnabled(context, "bob", true)
        assertFalse(MusterPresentation.enabled(context, "alice"))
        val notification = MusterNotifications.buildNotification(context, "alice", panel, session, true)
        assertNull(notification.fullScreenIntent)
        assertEquals(3, notification.actions.size)
    }
    @Test fun realChecksAndDrillsKeepTheirOwnModeInTheNotificationAndDestination() {
        val live = MusterNotifications.buildNotification(context, "alice", panel, session, true, "live")
        val drill = MusterNotifications.buildNotification(context, "alice", panel, session, true, "drill")
        assertEquals(context.getString(R.string.muster_title), live.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(context.getString(R.string.muster_drill_title), drill.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("drill", shadowOf(drill.contentIntent).savedIntent.getStringExtra("kind"))
    }
    @Test fun inAppPresentationNeverTakesOverAnotherAppOrOverridesAlertPreferences() {
        assertTrue(MusterPresentation.shouldPresentWithinApp(true, true, true, true, true))
        assertFalse(MusterPresentation.shouldPresentWithinApp(true, true, true, true, false))
        assertFalse(MusterPresentation.shouldPresentWithinApp(false, true, true, true, true))
        assertFalse(MusterPresentation.shouldPresentWithinApp(true, false, true, true, true))
        assertFalse(MusterPresentation.shouldPresentWithinApp(true, true, false, true, true))
        assertFalse(MusterPresentation.shouldPresentWithinApp(true, true, true, false, true))
    }

}
